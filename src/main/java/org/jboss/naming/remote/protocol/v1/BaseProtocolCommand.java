/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.naming.remote.protocol.v1;

import java.io.DataInput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.naming.remote.protocol.ProtocolCommand;
import static org.jboss.naming.remote.protocol.v1.Constants.EXCEPTION;
import static org.jboss.naming.remote.protocol.v1.Constants.FAILURE;
import static org.jboss.naming.remote.protocol.v1.Constants.SUCCESS;
import static org.jboss.naming.remote.protocol.v1.Constants.VOID;
import static org.jboss.naming.remote.protocol.v1.ReadUtil.prepareForUnMarshalling;

/**
 * @author John Bailey
 */
abstract class BaseProtocolCommand<T> implements ProtocolCommand<T> {
    public static final int DEFAULT_TIMEOUT = 10;

    private int nextCorrelationId = 1;
    private final Map<Integer, ProtocolIoFuture> requests = new HashMap<Integer, ProtocolIoFuture>();

    private final byte commandId;

    protected BaseProtocolCommand(byte commandId) {
        this.commandId = commandId;
    }

    public byte getCommandId() {
        return commandId;
    }

    protected <T> void readResult(final int correlationId, final DataInput input, final ValueReader<T> valueReader) throws IOException {
        final ProtocolIoFuture<T> future = (ProtocolIoFuture<T>) getFuture(correlationId);
        try {
            byte outcome = input.readByte();
            if (outcome == SUCCESS) {
                valueReader.read(input, future);
            } else if (outcome == FAILURE) {
                byte parameterType = input.readByte();
                if (parameterType != EXCEPTION) {
                    throw new IOException("Unexpected response parameter received.");
                }
                final Unmarshaller unmarshaller = prepareForUnMarshalling(input);
                final Exception exception = unmarshaller.readObject(Exception.class);
                future.setHeldException(exception);
            } else {
                future.setException(new IOException("Outcome not understood"));
            }
        } catch (ClassCastException e) {
            future.setException(new IOException(e));
        } catch (ClassNotFoundException e) {
            future.setException(new IOException(e));
        } catch (IOException e) {
            future.setException(e);
        }
    }

    private synchronized int getNextCorrelationId() {
        int next = nextCorrelationId++;
        // After the maximum integer start back at the beginning.
        if (next < 0) {
            nextCorrelationId = 2;
            next = 1;
        }
        return next;
    }

    protected synchronized int reserveNextCorrelationId(ProtocolIoFuture<?> future) {
        Integer next = getNextCorrelationId();
        while (requests.containsKey(next)) {
            next = getNextCorrelationId();
        }
        requests.put(next, future);

        return next;
    }

    private synchronized ProtocolIoFuture<?> getFuture(final int correlationId) {
        return requests.get(correlationId);
    }

    protected synchronized void releaseCorrelationId(int correlationId) {
        requests.remove(correlationId);
    }


    protected interface ValueReader<T> {
        void read(DataInput input, ProtocolIoFuture<T> future) throws IOException;
    }

    protected class MarshalledValueReader<T> implements ValueReader<T> {
        private final byte expectedType;

        public MarshalledValueReader(final byte expectedType) {
            this.expectedType = expectedType;
        }

        public byte getExpectedType() {
            return expectedType;
        }

        @SuppressWarnings("unchecked")
        public void read(DataInput input, ProtocolIoFuture<T> future) throws IOException {
            if (expectedType != VOID) {
                byte parameterType = input.readByte();
                if (parameterType != expectedType) {
                    throw new IOException("Unexpected response parameter received.");
                }
                final Unmarshaller unmarshaller = prepareForUnMarshalling(input);
                try {
                    future.setResult((T)unmarshaller.readObject());
                } catch (ClassNotFoundException e) {
                    throw new IOException(e);
                } catch (ClassCastException e) {
                    throw new IOException(e);
                }
            }
        }
    }
}
