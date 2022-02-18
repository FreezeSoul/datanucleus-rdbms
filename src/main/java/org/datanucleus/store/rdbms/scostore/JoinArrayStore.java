/**********************************************************************
Copyright (c) 2009 Andy Jefferson and others. All rights reserved.
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
package org.datanucleus.store.rdbms.scostore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlan;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.DiscriminatorStrategy;
import org.datanucleus.metadata.FieldRole;
import org.datanucleus.metadata.MetaDataUtils;
import org.datanucleus.state.DNStateManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.rdbms.exceptions.MappedDatastoreException;
import org.datanucleus.store.rdbms.mapping.java.ReferenceMapping;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.query.PersistentClassROF;
import org.datanucleus.store.rdbms.query.ResultObjectFactory;
import org.datanucleus.store.rdbms.query.StatementClassMapping;
import org.datanucleus.store.rdbms.query.StatementMappingIndex;
import org.datanucleus.store.rdbms.query.StatementParameterMapping;
import org.datanucleus.store.rdbms.sql.DiscriminatorStatementGenerator;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLStatementHelper;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.SelectStatementGenerator;
import org.datanucleus.store.rdbms.sql.UnionStatementGenerator;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.ArrayTable;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;

/**
 * Implementation of a Join ArrayStore
 */
public class JoinArrayStore<E> extends AbstractArrayStore<E>
{
    /**
     * Constructor for an RDBMS implementation of a join array store.
     * @param mmd Metadata for the owning field/property
     * @param arrayTable The Join table
     * @param clr ClassLoader resolver
     */
    public JoinArrayStore(AbstractMemberMetaData mmd, ArrayTable arrayTable, ClassLoaderResolver clr)
    {
        super(arrayTable.getStoreManager(), clr);

        this.containerTable = arrayTable;
        setOwner(arrayTable.getOwnerMemberMetaData());

        this.ownerMapping = arrayTable.getOwnerMapping();
        this.elementMapping = arrayTable.getElementMapping();
        this.orderMapping = arrayTable.getOrderMapping();
        this.relationDiscriminatorMapping = arrayTable.getRelationDiscriminatorMapping();
        this.relationDiscriminatorValue = arrayTable.getRelationDiscriminatorValue();

        this.elementType = arrayTable.getElementType();
        this.elementsAreEmbedded = arrayTable.isEmbeddedElement();
        this.elementsAreSerialised = arrayTable.isSerialisedElement();

        if (this.elementsAreSerialised)
        {
            this.elementInfo = null;
        }
        else
        {
            Class element_class=clr.classForName(elementType);
            if (ClassUtils.isReferenceType(element_class))
            {
                // Array of reference types (interfaces/Objects)
                String[] implNames = MetaDataUtils.getInstance().getImplementationNamesForReferenceField(ownerMemberMetaData,
                    FieldRole.ROLE_ARRAY_ELEMENT, clr, storeMgr.getMetaDataManager());
                elementInfo = new ComponentInfo[implNames.length];
                for (int i=0;i<implNames.length;i++)
                {
                    DatastoreClass table = storeMgr.getDatastoreClass(implNames[i], clr);
                    AbstractClassMetaData cmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(implNames[i], clr);
                    elementInfo[i] = new ComponentInfo(cmd, table);
                }
            }
            else
            {
                // Collection of PC or non-PC
                elementCmd = storeMgr.getNucleusContext().getMetaDataManager().getMetaDataForClass(element_class, clr);
                if (elementCmd != null)
                {
                    this.elementType  = elementCmd.getFullClassName();
                    if (!elementsAreEmbedded && !elementsAreSerialised)
                    {
                        elementInfo = getComponentInformationForClass(elementType, elementCmd);
                        if (elementInfo != null && elementInfo.length > 1)
                        {
                            throw new NucleusUserException(Localiser.msg("056045", ownerMemberMetaData.getFullFieldName()));
                        }
                    }
                    else
                    {
                        elementInfo = null;
                    }
                }
                else
                {
                    elementInfo = null;
                }
            }
        }
    }

    @Override
    public Iterator<E> iterator(DNStateManager ownerSM)
    {
        ExecutionContext ec = ownerSM.getExecutionContext();

        // Generate the statement, and statement mapping/parameter information
        ElementIteratorStatement iterStmt = getIteratorStatement(ec, ec.getFetchPlan(), true);
        SelectStatement sqlStmt = iterStmt.sqlStmt;
        StatementClassMapping iteratorMappingClass = iterStmt.elementClassMapping;

        // Input parameter(s) - the owner
        int inputParamNum = 1;
        StatementMappingIndex ownerIdx = new StatementMappingIndex(ownerMapping);
        if (sqlStmt.getNumberOfUnions() > 0)
        {
            // Add parameter occurrence for each union of statement
            for (int j=0;j<sqlStmt.getNumberOfUnions()+1;j++)
            {
                int[] paramPositions = new int[ownerMapping.getNumberOfColumnMappings()];
                for (int k=0;k<paramPositions.length;k++)
                {
                    paramPositions[k] = inputParamNum++;
                }
                ownerIdx.addParameterOccurrence(paramPositions);
            }
        }
        else
        {
            int[] paramPositions = new int[ownerMapping.getNumberOfColumnMappings()];
            for (int k=0;k<paramPositions.length;k++)
            {
                paramPositions[k] = inputParamNum++;
            }
            ownerIdx.addParameterOccurrence(paramPositions);
        }

        StatementParameterMapping iteratorMappingParams = new StatementParameterMapping();
        iteratorMappingParams.addMappingForParameter("owner", ownerIdx);

        if (ec.getTransaction().getSerializeRead() != null && ec.getTransaction().getSerializeRead())
        {
            sqlStmt.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, true);
        }
        String stmt = sqlStmt.getSQLText().toSQL();

        try
        {
            ManagedConnection mconn = storeMgr.getConnectionManager().getConnection(ec);
            SQLController sqlControl = storeMgr.getSQLController();
            try
            {
                // Create the statement
                PreparedStatement ps = sqlControl.getStatementForQuery(mconn, stmt);

                // Set the owner
                DNStateManager stmtOwnerSM = BackingStoreHelper.getOwnerStateManagerForBackingStore(ownerSM);
                int numParams = ownerIdx.getNumberOfParameterOccurrences();
                for (int paramInstance=0;paramInstance<numParams;paramInstance++)
                {
                    ownerIdx.getMapping().setObject(ec, ps, ownerIdx.getParameterPositionsForOccurrence(paramInstance), stmtOwnerSM.getObject());
                }

                try
                {
                    ResultSet rs = sqlControl.executeStatementQuery(ec, mconn, stmt, ps);
                    try
                    {
                        if (elementsAreEmbedded || elementsAreSerialised)
                        {
                            // No ResultObjectFactory needed - handled by SetStoreIterator
                            return new ArrayStoreIterator(ownerSM, rs, null, this);
                        }
                        else if (elementMapping instanceof ReferenceMapping)
                        {
                            // No ResultObjectFactory needed - handled by SetStoreIterator
                            return new ArrayStoreIterator(ownerSM, rs, null, this);
                        }
                        else
                        {
                            ResultObjectFactory rof = new PersistentClassROF(ec, rs, false, ec.getFetchPlan(), iteratorMappingClass, elementCmd, clr.classForName(elementType));
                            return new ArrayStoreIterator(ownerSM, rs, rof, this);
                        }
                    }
                    finally
                    {
                        rs.close();
                    }
                }
                finally
                {
                    sqlControl.closeStatement(mconn, ps);
                }
            }
            finally
            {
                mconn.release();
            }
        }
        catch (SQLException | MappedDatastoreException e)
        {
            throw new NucleusDataStoreException(Localiser.msg("056006", stmt),e);
        }
    }

    /**
     * Method to return the SQLStatement and mapping for an iterator for this backing store.
     * Create a statement of the form
     * <pre>
     * SELECT ELEM_COLS
     * FROM JOIN_TBL
     *   [JOIN ELEM_TBL ON ELEM_TBL.ID = JOIN_TBL.ELEM_ID]
     * [WHERE]
     *   [JOIN_TBL.OWNER_ID = {value}] [AND]
     *   [JOIN_TBL.DISCRIM = {discrimValue}]
     * [ORDER BY {orderClause}]
     * </pre>
     * @param ec ExecutionContext
     * @param fp FetchPlan to use in determing which fields of element to select
     * @param addRestrictionOnOwner Whether to restrict to a particular owner (otherwise functions as bulk fetch for many owners).
     * @return The SQLStatement and its associated StatementClassMapping
     */
    public ElementIteratorStatement getIteratorStatement(ExecutionContext ec, FetchPlan fp, boolean addRestrictionOnOwner)
    {
        SelectStatement sqlStmt = null;
        SQLExpressionFactory exprFactory = storeMgr.getSQLExpressionFactory();
        StatementClassMapping elementClsMapping = null;

        if (elementsAreEmbedded || elementsAreSerialised)
        {
            // Element = embedded, serialised (maybe Non-PC)
            // Just select the join table since we're going to return the embedded/serialised columns from it
            sqlStmt = new SelectStatement(storeMgr, containerTable, null, null);
            sqlStmt.setClassLoaderResolver(clr);

            // Select the element column - first select is assumed by SetStoreIterator
            sqlStmt.select(sqlStmt.getPrimaryTable(), elementMapping, null);
            // TODO If embedded element and it includes 1-1/N-1 in FetchPlan then select its fields also
        }
        else if (elementMapping instanceof ReferenceMapping)
        {
            // Element = Reference type (interface/Object)
            // Just select the join table since we're going to return the implementation id columns only
            sqlStmt = new SelectStatement(storeMgr, containerTable, null, null);
            sqlStmt.setClassLoaderResolver(clr);

            // Select the reference column(s) - first select is assumed by SetStoreIterator
            sqlStmt.select(sqlStmt.getPrimaryTable(), elementMapping, null);
        }
        else
        {
            // Element = PC
            // Join to the element table(s)
            elementClsMapping = new StatementClassMapping();
            for (int i = 0; i < elementInfo.length; i++)
            {
                // TODO This will only work if all element types have a discriminator
                final int elementNo = i;
                final Class elementCls = clr.classForName(elementInfo[elementNo].getClassName());
                SelectStatement elementStmt = null;
                if (elementInfo[elementNo].getDiscriminatorStrategy() != null &&
                    elementInfo[elementNo].getDiscriminatorStrategy() != DiscriminatorStrategy.NONE)
                {
                    // The element uses a discriminator so just use that in the SELECT
                    String elementType = ownerMemberMetaData.getCollection().getElementType();
                    if (ClassUtils.isReferenceType(clr.classForName(elementType)))
                    {
                        String[] clsNames = storeMgr.getNucleusContext().getMetaDataManager().getClassesImplementingInterface(elementType, clr);
                        Class[] cls = new Class[clsNames.length];
                        for (int j = 0; j < clsNames.length; j++)
                        {
                            cls[j] = clr.classForName(clsNames[j]);
                        }

                        SelectStatementGenerator stmtGen = new DiscriminatorStatementGenerator(storeMgr, clr, cls, true, null, null, containerTable, null, elementMapping);
                        if (allowNulls)
                        {
                            stmtGen.setOption(SelectStatementGenerator.OPTION_ALLOW_NULLS);
                        }
                        elementStmt = stmtGen.getStatement(ec);
                    }
                    else
                    {
                        SelectStatementGenerator stmtGen = new DiscriminatorStatementGenerator(storeMgr, clr, elementCls, true, null, null, containerTable, null, elementMapping);
                        if (allowNulls)
                        {
                            stmtGen.setOption(SelectStatementGenerator.OPTION_ALLOW_NULLS);
                        }
                        elementStmt = stmtGen.getStatement(ec);
                    }
                    iterateUsingDiscriminator = true;
                }
                else
                {
                    // No discriminator, but subclasses so use UNIONs
                    SelectStatementGenerator stmtGen = new UnionStatementGenerator(storeMgr, clr, elementCls, true, null, null, containerTable, null, elementMapping);
                    stmtGen.setOption(SelectStatementGenerator.OPTION_SELECT_DN_TYPE);
                    if (allowNulls) 
                    {
                        stmtGen.setOption(SelectStatementGenerator.OPTION_ALLOW_NULLS);
                    }
                    elementClsMapping.setNucleusTypeColumnName(UnionStatementGenerator.DN_TYPE_COLUMN);
                    elementStmt = stmtGen.getStatement(ec);
                }

                if (sqlStmt == null)
                {
                    sqlStmt = elementStmt;
                }
                else
                {
                    sqlStmt.union(elementStmt);
                }
            }
            if (sqlStmt == null)
            {
                throw new NucleusException("Error in generation of SQL statement for iterator over (Join) array. Statement is null");
            }

            // Select the required fields
            SQLTable elementSqlTbl = sqlStmt.getTable(elementInfo[0].getDatastoreClass(), sqlStmt.getPrimaryTable().getGroupName());
            SQLStatementHelper.selectFetchPlanOfSourceClassInStatement(sqlStmt, elementClsMapping, fp, elementSqlTbl, elementCmd, fp.getMaxFetchDepth());
        }

        if (addRestrictionOnOwner)
        {
            // Apply condition on join-table owner field to filter by owner
            SQLTable ownerSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), ownerMapping);
            SQLExpression ownerExpr = exprFactory.newExpression(sqlStmt, ownerSqlTbl, ownerMapping);
            SQLExpression ownerVal = exprFactory.newLiteralParameter(sqlStmt, ownerMapping, null, "OWNER");
            sqlStmt.whereAnd(ownerExpr.eq(ownerVal), true);
        }

        if (relationDiscriminatorMapping != null)
        {
            // Apply condition on distinguisher field to filter by distinguisher (when present)
            SQLTable distSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), relationDiscriminatorMapping);
            SQLExpression distExpr = exprFactory.newExpression(sqlStmt, distSqlTbl, relationDiscriminatorMapping);
            SQLExpression distVal = exprFactory.newLiteral(sqlStmt, relationDiscriminatorMapping, relationDiscriminatorValue);
            sqlStmt.whereAnd(distExpr.eq(distVal), true);
        }

        if (orderMapping != null)
        {
            // Order by the ordering column, when present
            SQLTable orderSqlTbl = SQLStatementHelper.getSQLTableForMappingOfTable(sqlStmt, sqlStmt.getPrimaryTable(), orderMapping);
            SQLExpression[] orderExprs = new SQLExpression[orderMapping.getNumberOfColumnMappings()];
            boolean descendingOrder[] = new boolean[orderMapping.getNumberOfColumnMappings()];
            orderExprs[0] = exprFactory.newExpression(sqlStmt, orderSqlTbl, orderMapping);
            sqlStmt.setOrdering(orderExprs, descendingOrder);
        }

        return new ElementIteratorStatement(this, sqlStmt, elementClsMapping);
    }
}