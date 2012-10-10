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

package kafka.log

import java.nio._
import java.util.concurrent.atomic._
import junit.framework.Assert._
import kafka.utils.TestUtils._
import kafka.message._
import org.junit.Test

class FileMessageSetTest extends BaseMessageSetTestCases {
  
  val messageSet = createMessageSet(messages)
  
  def createMessageSet(messages: Seq[Message]): FileMessageSet = {
    val set = new FileMessageSet(tempFile(), true)
    set.append(new ByteBufferMessageSet(NoCompressionCodec, messages: _*))
    set.flush()
    set
  }

  @Test
  def testFileSize() {
    assertEquals(messageSet.channel.size, messageSet.sizeInBytes)
    messageSet.append(singleMessageSet("abcd".getBytes()))
    assertEquals(messageSet.channel.size, messageSet.sizeInBytes)
  }
  
  @Test
  def testIterationOverPartialAndTruncation() {
    testPartialWrite(0, messageSet)
    testPartialWrite(2, messageSet)
    testPartialWrite(4, messageSet)
    testPartialWrite(5, messageSet)
    testPartialWrite(6, messageSet)
  }
  
  def testPartialWrite(size: Int, messageSet: FileMessageSet) {
    val buffer = ByteBuffer.allocate(size)
    val originalPosition = messageSet.channel.position
    for(i <- 0 until size)
      buffer.put(0.asInstanceOf[Byte])
    buffer.rewind()
    messageSet.channel.write(buffer)
    // appending those bytes should not change the contents
    checkEquals(messages.iterator, messageSet.map(m => m.message).iterator)
  }
  
  @Test
  def testIterationDoesntChangePosition() {
    val position = messageSet.channel.position
    checkEquals(messages.iterator, messageSet.map(m => m.message).iterator)
    assertEquals(position, messageSet.channel.position)
  }
  
  @Test
  def testRead() {
    val read = messageSet.read(0, messageSet.sizeInBytes)
    checkEquals(messageSet.iterator, read.iterator)
    val items = read.iterator.toList
    val sec = items.tail.head
    val read2 = messageSet.read(MessageSet.entrySize(sec.message), messageSet.sizeInBytes)
    checkEquals(items.tail.iterator, read2.iterator)
  }
  
  @Test
  def testSearch() {
    // append a new message with a high offset
    val lastMessage = new Message("test".getBytes)
    messageSet.append(new ByteBufferMessageSet(NoCompressionCodec, new AtomicLong(50), lastMessage))
    var physicalOffset = 0
    assertEquals("Should be able to find the first message by its offset", 
                 OffsetPosition(0L, physicalOffset), 
                 messageSet.searchFor(0, 0))
    physicalOffset += MessageSet.entrySize(messageSet.head.message)
    assertEquals("Should be able to find second message when starting from 0", 
                 OffsetPosition(1L, physicalOffset), 
                 messageSet.searchFor(1, 0))
    assertEquals("Should be able to find second message starting from its offset", 
                 OffsetPosition(1L, physicalOffset), 
                 messageSet.searchFor(1, physicalOffset))
    physicalOffset += MessageSet.entrySize(messageSet.tail.head.message)
    assertEquals("Should be able to find third message from a non-existant offset", 
                 OffsetPosition(50L, physicalOffset), 
                 messageSet.searchFor(3, physicalOffset))
    assertEquals("Should be able to find third message by correct offset", 
                 OffsetPosition(50L, physicalOffset), 
                 messageSet.searchFor(50, physicalOffset))
  }
  
}
