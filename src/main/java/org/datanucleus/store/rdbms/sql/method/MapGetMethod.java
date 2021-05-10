/**********************************************************************
Copyright (c) 2008 Andy Jefferson and others. All rights reserved.
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

import java.util.List;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.MapMetaData;
import org.datanucleus.metadata.MapMetaData.MapType;
import org.datanucleus.store.rdbms.mapping.MappingType;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.query.compiler.CompilationComponent;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.SQLJoin.JoinType;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.MapExpression;
import org.datanucleus.store.rdbms.sql.expression.MapLiteral;
import org.datanucleus.store.rdbms.sql.expression.NullLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.sql.expression.SQLLiteral;
import org.datanucleus.store.rdbms.sql.expression.SubqueryExpression;
import org.datanucleus.store.rdbms.sql.expression.UnboundExpression;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.rdbms.table.MapTable;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.util.Localiser;

/**
 * Method for evaluating {mapExpr}.get(keyExpr).
 * Returns an ObjectExpression representing the value
 */
public class MapGetMethod implements SQLMethod
{
    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.sql.method.SQLMethod#getExpression(org.datanucleus.store.rdbms.sql.expression.SQLExpression, java.util.List)
     */
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        if (args == null || args.size() == 0 || args.size() > 1)
        {
            throw new NucleusException(Localiser.msg("060016", "get", "MapExpression", 1));
        }

        MapExpression mapExpr = (MapExpression)expr;
        SQLExpression keyValExpr = args.get(0);
        if (keyValExpr instanceof UnboundExpression)
        {
            // TODO Add support for unbound variables (as per CollectionContains)
            throw new NucleusException("Dont currently support binding of unbound variables using Map.get");
        }

        if (mapExpr instanceof MapLiteral && keyValExpr instanceof SQLLiteral)
        {
            MapLiteral lit = (MapLiteral)expr;
            if (lit.getValue() == null)
            {
                return new NullLiteral(stmt, null, null, null);
            }

            return lit.getKeyLiteral().invoke("get", args);
        }
        else if (mapExpr instanceof MapLiteral)
        {
            // MapLiteral.get(SQLExpression)
            throw new NucleusUserException("We do not support MapLiteral.get(SQLExpression) since SQL doesnt allow such constructs");
        }
        else
        {
            if (stmt.getQueryGenerator().getCompilationComponent() == CompilationComponent.FILTER ||
                stmt.getQueryGenerator().getCompilationComponent() == CompilationComponent.ORDERING)
            {
                return getAsInnerJoin(stmt, mapExpr, keyValExpr);
            }
            else if (stmt.getQueryGenerator().getCompilationComponent() == CompilationComponent.RESULT ||
                    stmt.getQueryGenerator().getCompilationComponent() == CompilationComponent.HAVING)
            {
                return getAsSubquery(stmt, mapExpr, keyValExpr);
            }

            throw new NucleusException("Map.get() is not supported for " + mapExpr +
                " with argument " + keyValExpr + " for query component " + stmt.getQueryGenerator().getCompilationComponent());
        }
    }

    /**
     * Implementation of Map.get() using a subquery on the table representing the map,
     * adding a condition on the key and returning the value.
     * @param stmt SQLStatement
     * @param mapExpr The map expression
     * @param keyValExpr The key value expression
     * @return The value expression
     */
    protected SQLExpression getAsSubquery(SQLStatement stmt, MapExpression mapExpr, SQLExpression keyValExpr)
    {
        ClassLoaderResolver clr = stmt.getQueryGenerator().getClassLoaderResolver();
        AbstractMemberMetaData mmd = mapExpr.getJavaTypeMapping().getMemberMetaData();
        MapMetaData mapmd = mmd.getMap();
        RDBMSStoreManager storeMgr = stmt.getRDBMSManager();

        JavaTypeMapping ownerMapping = null;
        JavaTypeMapping keyMapping = null;
        JavaTypeMapping valMapping = null;
        Table mapTbl = null;
        if (mapmd.getMapType() == MapType.MAP_TYPE_JOIN)
        {
            // JoinTable
            mapTbl = storeMgr.getTable(mmd);
            ownerMapping = ((MapTable)mapTbl).getOwnerMapping();
            keyMapping = ((MapTable)mapTbl).getKeyMapping();
            valMapping = ((MapTable)mapTbl).getValueMapping();
        }
        else if (mapmd.getMapType() == MapType.MAP_TYPE_KEY_IN_VALUE)
        {
            // ForeignKey from value table to key
            AbstractClassMetaData valCmd = mapmd.getValueClassMetaData(clr);
            mapTbl = storeMgr.getDatastoreClass(mmd.getMap().getValueType(), clr);
            if (mmd.getMappedBy() != null)
            {
                ownerMapping = mapTbl.getMemberMapping(valCmd.getMetaDataForMember(mmd.getMappedBy()));
            }
            else
            {
                ownerMapping = ((DatastoreClass)mapTbl).getExternalMapping(mmd, MappingType.EXTERNAL_FK);
            }
            String keyFieldName = mmd.getKeyMetaData().getMappedBy();
            AbstractMemberMetaData valKeyMmd = valCmd.getMetaDataForMember(keyFieldName);
            keyMapping = mapTbl.getMemberMapping(valKeyMmd);
            valMapping = mapTbl.getIdMapping();
        }
        else if (mapmd.getMapType() == MapType.MAP_TYPE_VALUE_IN_KEY)
        {
            // ForeignKey from key table to value
            AbstractClassMetaData keyCmd = mapmd.getKeyClassMetaData(clr);
            mapTbl = storeMgr.getDatastoreClass(mmd.getMap().getKeyType(), clr);
            if (mmd.getMappedBy() != null)
            {
                ownerMapping = mapTbl.getMemberMapping(keyCmd.getMetaDataForMember(mmd.getMappedBy()));
            }
            else
            {
                ownerMapping = ((DatastoreClass)mapTbl).getExternalMapping(mmd, MappingType.EXTERNAL_FK);
            }
            keyMapping = mapTbl.getIdMapping();
            String valFieldName = mmd.getValueMetaData().getMappedBy();
            AbstractMemberMetaData keyValMmd = keyCmd.getMetaDataForMember(valFieldName);
            valMapping = mapTbl.getMemberMapping(keyValMmd);
        }
        else
        {
            throw new NucleusException("Invalid map for " + mapExpr + " in get() call");
        }

        SelectStatement subStmt = new SelectStatement(stmt, storeMgr, mapTbl, null, null);
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        subStmt.setClassLoaderResolver(clr);
        SQLExpression valExpr = exprFactory.newExpression(subStmt, subStmt.getPrimaryTable(), valMapping);
        subStmt.select(valExpr, null);

        // Link to primary statement
        SQLExpression elementOwnerExpr = exprFactory.newExpression(subStmt, subStmt.getPrimaryTable(), ownerMapping);
        SQLExpression ownerIdExpr = exprFactory.newExpression(stmt, mapExpr.getSQLTable(), mapExpr.getSQLTable().getTable().getIdMapping());
        subStmt.whereAnd(elementOwnerExpr.eq(ownerIdExpr), true);

        // Condition on key
        SQLExpression keyExpr = exprFactory.newExpression(subStmt, subStmt.getPrimaryTable(), keyMapping);
        subStmt.whereAnd(keyExpr.eq(keyValExpr), true);

        SubqueryExpression subExpr = new SubqueryExpression(stmt, subStmt);
        subExpr.setJavaTypeMapping(valMapping);
        return subExpr;
    }

    /**
     * Implementation of Map.get() using an inner join to the table representing the map, adding a condition on the key and returning the value.
     * @param stmt SQLStatement
     * @param mapExpr The map expression
     * @param keyValExpr The key value expression
     * @return The value expression
     */
    protected SQLExpression getAsInnerJoin(SQLStatement stmt, MapExpression mapExpr, SQLExpression keyValExpr)
    {
        ClassLoaderResolver clr = stmt.getQueryGenerator().getClassLoaderResolver();
        SQLExpressionFactory exprFactory = stmt.getSQLExpressionFactory();
        JavaTypeMapping m = mapExpr.getJavaTypeMapping();
        AbstractMemberMetaData mmd = m.getMemberMetaData();
        if (mmd != null)
        {
            MapMetaData mapmd = mmd.getMap();
            if (mapmd.getMapType() == MapType.MAP_TYPE_JOIN)
            {
                MapTable joinTbl = (MapTable)stmt.getRDBMSManager().getTable(mmd);

                // Add join to join table
                SQLTable joinSqlTbl = stmt.join(JoinType.INNER_JOIN, mapExpr.getSQLTable(), mapExpr.getSQLTable().getTable().getIdMapping(), 
                    joinTbl, null, joinTbl.getOwnerMapping(), null, null);

                // Add condition on key
                SQLExpression keyExpr = exprFactory.newExpression(stmt, joinSqlTbl, joinTbl.getKeyMapping());
                stmt.whereAnd(keyExpr.eq(keyValExpr), true);

                // Return value expression
                if (mapmd.getValueClassMetaData(clr) != null)
                {
                    // Persistable value so join to its table
                    DatastoreClass valTable = stmt.getRDBMSManager().getDatastoreClass(mapmd.getValueType(), clr);
                    SQLTable valueSqlTbl = stmt.join(JoinType.INNER_JOIN, joinSqlTbl, joinTbl.getValueMapping(), valTable, null, valTable.getIdMapping(), null, null);

                    return exprFactory.newExpression(stmt, valueSqlTbl, valTable.getIdMapping());
                }

                // Return mapping for the value in the join table
                SQLExpression valueExpr = exprFactory.newExpression(stmt, joinSqlTbl, joinTbl.getValueMapping());
                return valueExpr;
            }
            else if (mapmd.getMapType() == MapType.MAP_TYPE_KEY_IN_VALUE)
            {
                // Key stored in value table, so join to value table
                DatastoreClass valTable = stmt.getRDBMSManager().getDatastoreClass(mapmd.getValueType(), clr);
                AbstractClassMetaData valCmd = mapmd.getValueClassMetaData(clr);
                JavaTypeMapping mapTblOwnerMapping;
                if (mmd.getMappedBy() != null)
                {
                    mapTblOwnerMapping = valTable.getMemberMapping(valCmd.getMetaDataForMember(mmd.getMappedBy()));
                }
                else
                {
                    mapTblOwnerMapping = valTable.getExternalMapping(mmd, MappingType.EXTERNAL_FK);
                }
                SQLTable valSqlTbl = stmt.join(JoinType.INNER_JOIN, mapExpr.getSQLTable(), mapExpr.getSQLTable().getTable().getIdMapping(), valTable, null, mapTblOwnerMapping, null, null);

                // Add condition on key
                JavaTypeMapping keyMapping = valTable.getMemberMapping(valCmd.getMetaDataForMember(mmd.getKeyMetaData().getMappedBy()));
                SQLExpression keyExpr = exprFactory.newExpression(stmt, valSqlTbl, keyMapping);
                stmt.whereAnd(keyExpr.eq(keyValExpr), true);

                // Return value expression
                SQLExpression valueExpr = exprFactory.newExpression(stmt, valSqlTbl, valTable.getIdMapping());
                return valueExpr;
            }
            else if (mapmd.getMapType() == MapType.MAP_TYPE_VALUE_IN_KEY)
            {
                // Value stored in key table, so join to key table
                DatastoreClass keyTable = stmt.getRDBMSManager().getDatastoreClass(mapmd.getKeyType(), clr);
                AbstractClassMetaData keyCmd = mapmd.getKeyClassMetaData(clr);
                JavaTypeMapping mapTblOwnerMapping;
                if (mmd.getMappedBy() != null)
                {
                    mapTblOwnerMapping = keyTable.getMemberMapping(keyCmd.getMetaDataForMember(mmd.getMappedBy()));
                }
                else
                {
                    mapTblOwnerMapping = keyTable.getExternalMapping(mmd, MappingType.EXTERNAL_FK);
                }
                SQLTable keySqlTbl = stmt.join(JoinType.INNER_JOIN, mapExpr.getSQLTable(), mapExpr.getSQLTable().getTable().getIdMapping(), keyTable, null, mapTblOwnerMapping, null, null);

                // Add condition on key
                SQLExpression keyExpr = exprFactory.newExpression(stmt, keySqlTbl, keyTable.getIdMapping());
                stmt.whereAnd(keyExpr.eq(keyValExpr), true);

                // Return value expression
                JavaTypeMapping valueMapping = keyTable.getMemberMapping(keyCmd.getMetaDataForMember(mmd.getValueMetaData().getMappedBy()));
                SQLExpression valueExpr = exprFactory.newExpression(stmt, keySqlTbl, valueMapping);
                return valueExpr;
            }
        }
        throw new NucleusException("Map.get() for the filter is not supported for " + mapExpr +
            " with an argument of " + keyValExpr + ". Why not contribute support for it?");
    }
}