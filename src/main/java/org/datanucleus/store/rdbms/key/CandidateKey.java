/**********************************************************************
Copyright (c) 2003 Mike Martin (TJDO) and others. All rights reserved. 
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
2004 Andy Jefferson - equality operator
2004 Erik Bengtson - addInheritedColumn() for app-id
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.key;

import org.datanucleus.store.rdbms.table.Table;

/**
 * Representation of a Candidate key.
 * This represents a UNIQUE candidate key on a column or columns.
 */
public class CandidateKey extends ColumnOrderedKey
{
    /**
     * Constructor.
     * @param table Table to apply this key to
     */
    public CandidateKey(Table table)
    {
        super(table);
    }

    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }
        if (!(obj instanceof CandidateKey))
        {
            return false;
        }

        return super.equals(obj);
    }

    public int hashCode()
    {
        return super.hashCode();
    }

    /**
     * Stringify method.
     * @return String version of this object.
     **/
    public String toString()
    {
        StringBuilder s = new StringBuilder("UNIQUE ").append(getColumnList(columns));

        return s.toString();
    }
}