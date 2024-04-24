/*
 * Copyright ${inceptionYear} - ${year} ${owner}
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.orientdb

import com.orientechnologies.orient.core.record.OVertex
import jetbrains.exodus.entitystore.PersistentEntityStore

class OReadonlyVertexEntity(val txn: OStoreTransaction, id: OEntityId) : OVertexEntity(
    txn.activeSession.load<OVertex>(id.asOId()), txn.store as PersistentEntityStore
) {
    override fun assertWritable() {
        super.assertWritable()
        throw IllegalArgumentException("Can't update readonly entity!")
    }
}

