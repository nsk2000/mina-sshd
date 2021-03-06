/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sshd.client.subsystem.sftp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.client.subsystem.sftp.SftpClient.CloseableHandle;
import org.apache.sshd.client.subsystem.sftp.SftpClient.Handle;
import org.apache.sshd.util.test.BaseTestSupport;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class DefaultCloseableHandleTest extends BaseTestSupport {
    public DefaultCloseableHandleTest() {
        super();
    }

    @Test
    public void testChannelBehavior() throws IOException {
        final byte[] id = getCurrentTestName().getBytes(StandardCharsets.UTF_8);
        SftpClient client = Mockito.mock(SftpClient.class);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                Handle handle = (Handle) args[0];
                assertArrayEquals("Mismatched closing handle", id, handle.getIdentifier());
                return null;
            }
        }).when(client).close(Matchers.any(Handle.class));

        CloseableHandle handle = new DefaultCloseableHandle(client, getCurrentTestName(), id);
        try {
            assertTrue("Handle not initially open", handle.isOpen());
        } finally {
            handle.close();
        }
        assertFalse("Handle not marked as closed", handle.isOpen());
        // make sure close was called
        Mockito.verify(client).close(handle);
    }

    @Test
    public void testCloseIdempotent() throws IOException {
        SftpClient client = Mockito.mock(SftpClient.class);
        final AtomicBoolean closeCalled = new AtomicBoolean(false);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                assertFalse("Close already called on handle=" + args[0], closeCalled.getAndSet(true));
                return null;
            }
        }).when(client).close(Matchers.any(Handle.class));

        CloseableHandle handle = new DefaultCloseableHandle(client, getCurrentTestName(), getCurrentTestName().getBytes(StandardCharsets.UTF_8));
        for (int index = 0; index < Byte.SIZE; index++) {
            handle.close();
        }

        assertTrue("Close method not called", closeCalled.get());
    }
}
