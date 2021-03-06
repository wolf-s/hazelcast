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

package com.hazelcast.map.operation;

import com.hazelcast.map.MapDataSerializerHook;
import com.hazelcast.map.record.Record;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.BackupOperation;

import java.io.IOException;

public final class PutBackupOperation extends KeyBasedMapOperation implements BackupOperation, IdentifiedDataSerializable {

    private boolean unlockKey = false;

    public PutBackupOperation(String name, Data dataKey, Data dataValue, long ttl) {
        super(name, dataKey, dataValue, ttl);
    }

    public PutBackupOperation(String name, Data dataKey, Data dataValue, long ttl, boolean unlockKey) {
        super(name, dataKey, dataValue, ttl);
        this.unlockKey = unlockKey;
    }

    public PutBackupOperation() {
    }

    public void run() {
        Record record = recordStore.getRecord(dataKey);
        if (record == null) {
            record = mapService.createRecord(name, dataKey, dataValue, ttl, false);
            updateSizeEstimator(calculateRecordSize(record));
            recordStore.putRecord(dataKey, record);
        } else {
            updateSizeEstimator(-calculateRecordSize(record));
            mapContainer.getRecordFactory().setValue(record, dataValue);
            updateSizeEstimator(calculateRecordSize(record));
        }
        if (unlockKey) {
            recordStore.forceUnlock(dataKey);
        }
    }

    @Override
    public Object getResponse() {
        return Boolean.TRUE;
    }

    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeBoolean(unlockKey);
    }

    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        unlockKey = in.readBoolean();
    }

    @Override
    public String toString() {
        return "PutBackupOperation{" + name + "}";
    }

    public int getFactoryId() {
        return MapDataSerializerHook.F_ID;
    }

    public int getId() {
        return MapDataSerializerHook.PUT_BACKUP;
    }

    private void updateSizeEstimator( long recordSize ) {
        recordStore.getSizeEstimator().add( recordSize );
    }

    private long calculateRecordSize( Record record ) {
        return recordStore.getSizeEstimator().getCost(record);
    }

}
