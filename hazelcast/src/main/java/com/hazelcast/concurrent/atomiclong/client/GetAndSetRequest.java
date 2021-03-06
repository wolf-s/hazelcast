/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.concurrent.atomiclong.client;

import com.hazelcast.concurrent.atomiclong.AtomicLongPortableHook;
import com.hazelcast.concurrent.atomiclong.GetAndSetOperation;
import com.hazelcast.spi.Operation;

/**
 * @author ali 5/13/13
 */
public class GetAndSetRequest extends AtomicLongRequest {

    public GetAndSetRequest() {
    }

    public GetAndSetRequest(String name, long value) {
        super(name, value);
    }

    protected Operation prepareOperation() {
        return new GetAndSetOperation(name, delta);
    }

    public int getClassId() {
        return AtomicLongPortableHook.GET_AND_SET;
    }
}
