/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.admin

import joptsimple.OptionParser
import kafka.utils._
import org.I0Itec.zkclient.ZkClient
import kafka.common.AdminCommandFailedException
import org.I0Itec.zkclient.exception.ZkNodeExistsException

object ReassignPartitionsCommand extends Logging {

  def main(args: Array[String]): Unit = {
    val parser = new OptionParser
    val jsonFileOpt = parser.accepts("path to json file", "REQUIRED: The JSON file with the list of partitions and the " +
      "new replicas they should be reassigned to in the following format - \n" +
       "[{\"topic\": \"foo\", \"partition\": \"1\", \"replicas\": \"1,2,3\" }]")
      .withRequiredArg
      .describedAs("partition reassignment json file path")
      .ofType(classOf[String])
    val zkConnectOpt = parser.accepts("zookeeper", "REQUIRED: The connection string for the zookeeper connection in the " +
      "form host:port. Multiple URLS can be given to allow fail-over.")
      .withRequiredArg
      .describedAs("urls")
      .ofType(classOf[String])

    val options = parser.parse(args : _*)

    for(arg <- List(jsonFileOpt, zkConnectOpt)) {
      if(!options.has(arg)) {
        System.err.println("Missing required argument \"" + arg + "\"")
        parser.printHelpOn(System.err)
        System.exit(1)
      }
    }

    val jsonFile = options.valueOf(jsonFileOpt)
    val zkConnect = options.valueOf(zkConnectOpt)
    val jsonString = Utils.readFileIntoString(jsonFile)
    var zkClient: ZkClient = null

    try {
      // read the json file into a string
      val partitionsToBeReassigned = SyncJSON.parseFull(jsonString) match {
        case Some(reassignedPartitions) =>
          val partitions = reassignedPartitions.asInstanceOf[Array[Map[String, String]]]
          partitions.map { m =>
            val topic = m.asInstanceOf[Map[String, String]].get("topic").get
            val partition = m.asInstanceOf[Map[String, String]].get("partition").get.toInt
            val replicasList = m.asInstanceOf[Map[String, String]].get("replicas").get
            val newReplicas = replicasList.split(",").map(_.toInt)
            ((topic, partition), newReplicas.toSeq)
          }.toMap
        case None => throw new AdminCommandFailedException("Partition reassignment data file %s is empty".format(jsonFile))
      }

      zkClient = new ZkClient(zkConnect, 30000, 30000, ZKStringSerializer)
      val reassignPartitionsCommand = new ReassignPartitionsCommand(zkClient, partitionsToBeReassigned)

      // attach shutdown handler to catch control-c
      Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() = {
          // delete the admin path so it can be retried
          ZkUtils.deletePathRecursive(zkClient, ZkUtils.ReassignPartitionsPath)
        }
      })

      if(reassignPartitionsCommand.reassignPartitions())
        println("Successfully started reassignment of partitions %s".format(partitionsToBeReassigned))
      else
        println("Failed to reassign partitions %s".format(partitionsToBeReassigned))
    } catch {
      case e =>
        println("Partitions reassignment failed due to " + e.getMessage)
        println(Utils.stackTrace(e))
    } finally {
      if (zkClient != null)
        zkClient.close()
    }
  }
}

class ReassignPartitionsCommand(zkClient: ZkClient, partitions: collection.immutable.Map[(String, Int), Seq[Int]])
  extends Logging {
  def reassignPartitions(): Boolean = {
    try {
      val validPartitions = partitions.filter(p => validatePartition(zkClient, p._1._1, p._1._2))
      val jsonReassignmentData = Utils.mapToJson(validPartitions.map(p =>
        ("%s,%s".format(p._1._1, p._1._2)) -> p._2.map(_.toString)))
      ZkUtils.createPersistentPath(zkClient, ZkUtils.ReassignPartitionsPath, jsonReassignmentData)
      true
    }catch {
      case ze: ZkNodeExistsException =>
        val partitionsBeingReassigned = ZkUtils.getPartitionsBeingReassigned(zkClient)
        throw new AdminCommandFailedException("Partition reassignment currently in " +
        "progress for %s. Aborting operation".format(partitionsBeingReassigned))
      case e => error("Admin command failed", e); false
    }
  }

  def validatePartition(zkClient: ZkClient, topic: String, partition: Int): Boolean = {
    // check if partition exists
    val partitionsOpt = ZkUtils.getPartitionsForTopics(zkClient, List(topic)).get(topic)
    partitionsOpt match {
      case Some(partitions) =>
        if(partitions.contains(partition)) {
          true
        }else{
          error("Skipping reassignment of partition [%s,%d] ".format(topic, partition) +
            "since it doesn't exist")
          false
        }
      case None => error("Skipping reassignment of partition " +
        "[%s,%d] since topic %s doesn't exist".format(topic, partition, topic))
        false
    }
  }
}

sealed trait ReassignmentStatus { def status: Int }
case object ReassignmentCompleted extends ReassignmentStatus { val status = 1 }
case object ReassignmentInProgress extends ReassignmentStatus { val status = 0 }
case object ReassignmentFailed extends ReassignmentStatus { val status = -1 }
