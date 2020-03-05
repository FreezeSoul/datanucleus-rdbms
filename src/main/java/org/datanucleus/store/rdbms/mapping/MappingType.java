/**********************************************************************
Copyright (c) 2017 Andy Jefferson and others. All rights reserved. 
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. 

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.mapping;

/**
 * Enum defining types of mappings, for use with a MappingConsumer.
 */
public enum MappingType
{
    /** (Surrogate) version column */
    VERSION,

    /** Datastore id column. */
    DATASTORE_ID,

    /** Discriminator column. */
    DISCRIMINATOR,

    /** Multitenancy column. */
    MULTITENANCY,

    /** Soft-delete flag column. */
    SOFTDELETE,

    /** Create-user audit column. */
    CREATEUSER,

    /** Update-user audit column. */
    UPDATEUSER,

    /** Create-timestamp audit column. */
    CREATETIMESTAMP,

    /** Update-timestamp audit column. */
    UPDATETIMESTAMP,

    /** List index from related class. */
    EXTERNAL_INDEX,

    /** FK from related class (N side of 1-N). */
    EXTERNAL_FK,

    /** Shared relation discriminator, from related class (N side of 1-N). */
    EXTERNAL_FK_DISCRIMINATOR
}