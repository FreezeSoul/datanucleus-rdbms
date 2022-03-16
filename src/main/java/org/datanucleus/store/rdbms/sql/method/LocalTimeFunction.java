/**********************************************************************
Copyright (c) 2022 Andy Jefferson and others. All rights reserved.
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
package org.datanucleus.store.rdbms.sql.method;

import java.time.LocalTime;
import java.util.List;

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.TemporalExpression;
import org.datanucleus.util.Localiser;

/**
 * Expression handler to invoke the SQL CURRENT_TIME function and return as LocalTime.
 * For use in evaluating LOCAL_TIME where the RDBMS supports the function CURRENT_TIME.
 * Returns a TemporalExpression "LOCAL_TIME".
 */
public class LocalTimeFunction implements SQLMethod
{
    protected String getFunctionName()
    {
        return "CURRENT_TIME";
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.sql.method.SQLMethod#getExpression(org.datanucleus.store.rdbms.sql.expression.SQLExpression, java.util.List)
     */
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        if (expr == null)
        {
            // Assume that we have something like "CURRENT_TIME()"
            SQLExpression dateExpr = new TemporalExpression(stmt, stmt.getSQLExpressionFactory().getMappingForType(getClassForMapping(), true), getFunctionName(), args);

            // Update the SQL manually since the default is to add brackets after the name
            dateExpr.toSQLText().clearStatement();
            dateExpr.toSQLText().append(getFunctionName());
            return dateExpr;
        }
        throw new NucleusException(Localiser.msg("060002", getFunctionName(), expr));
    }

    protected Class getClassForMapping()
    {
        return LocalTime.class;
    }
}