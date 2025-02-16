/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.rm.datasource.exec;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import com.alibaba.druid.util.JdbcConstants;
import io.seata.common.util.CollectionUtils;
import io.seata.common.util.StringUtils;
import io.seata.core.context.RootContext;
import io.seata.rm.datasource.ConnectionProxy;
import io.seata.rm.datasource.ParametersHolder;
import io.seata.rm.datasource.StatementProxy;
import io.seata.rm.datasource.sql.SQLRecognizer;
import io.seata.rm.datasource.sql.SQLType;
import io.seata.rm.datasource.sql.WhereRecognizer;
import io.seata.rm.datasource.sql.struct.Field;
import io.seata.rm.datasource.sql.struct.TableMeta;
import io.seata.rm.datasource.sql.struct.TableMetaCache;
import io.seata.rm.datasource.sql.struct.TableMetaCacheOracle;
import io.seata.rm.datasource.sql.struct.TableRecords;
import io.seata.rm.datasource.undo.SQLUndoLog;

/**
 * The type Base transactional executor.
 *
 * @author sharajava
 *
 * @param <T> the type parameter
 * @param <S> the type parameter
 */
public abstract class BaseTransactionalExecutor<T, S extends Statement> implements Executor {

    /**
     * The Statement proxy.
     */
    protected StatementProxy<S> statementProxy;

    /**
     * The Statement callback.
     */
    protected StatementCallback<T, S> statementCallback;

    /**
     * The Sql recognizer.
     */
    protected SQLRecognizer sqlRecognizer;

    private TableMeta tableMeta;

    /**
     * Instantiates a new Base transactional executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizer     the sql recognizer
     */
    public BaseTransactionalExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback,
                                     SQLRecognizer sqlRecognizer) {
        this.statementProxy = statementProxy;
        this.statementCallback = statementCallback;
        this.sqlRecognizer = sqlRecognizer;
    }

    @Override
    public Object execute(Object... args) throws Throwable {
        if (RootContext.inGlobalTransaction()) {
            String xid = RootContext.getXID();
            statementProxy.getConnectionProxy().bind(xid);
        }

        if (RootContext.requireGlobalLock()) {
            statementProxy.getConnectionProxy().setGlobalLockRequire(true);
        } else {
            statementProxy.getConnectionProxy().setGlobalLockRequire(false);
        }
        return doExecute(args);
    }

    /**
     * Do execute object.
     *
     * @param args the args
     * @return the object
     * @throws Throwable the throwable
     */
    protected abstract Object doExecute(Object... args) throws Throwable;

    /**
     * Build where condition by p ks string.
     *
     * @param pkRows the pk rows
     * @return the string
     * @throws SQLException the sql exception
     */
    protected String buildWhereConditionByPKs(List<Field> pkRows) throws SQLException {
        StringJoiner whereConditionAppender = new StringJoiner(" OR ");
        for (Field field : pkRows) {
            whereConditionAppender.add(getColumnNameInSQL(field.getName()) + " = ?");
        }
        return whereConditionAppender.toString();

    }

    /**
     * build buildWhereCondition
     *
     * @param recognizer        the recognizer
     * @param paramAppenderList the param paramAppender list
     * @return the string
     */
    protected String buildWhereCondition(WhereRecognizer recognizer, ArrayList<List<Object>> paramAppenderList) {
        String whereCondition = null;
        if (statementProxy instanceof ParametersHolder) {
            whereCondition = recognizer.getWhereCondition((ParametersHolder) statementProxy, paramAppenderList);
        } else {
            whereCondition = recognizer.getWhereCondition();
        }
        //process batch operation
        if (StringUtils.isNotBlank(whereCondition) && CollectionUtils.isNotEmpty(paramAppenderList) && paramAppenderList.size() > 1) {
            StringBuilder whereConditionSb = new StringBuilder();
            whereConditionSb.append(" ( ").append(whereCondition).append(" ) ");
            for (int i = 1; i < paramAppenderList.size(); i++) {
                whereConditionSb.append(" or ( ").append(whereCondition).append(" ) ");
            }
            whereCondition = whereConditionSb.toString();
        }
        return whereCondition;
    }

    /**
     * Gets column name in sql.
     *
     * @param columnName the column name
     * @return the column name in sql
     */
    protected String getColumnNameInSQL(String columnName) {
        String tableAlias = sqlRecognizer.getTableAlias();
        return tableAlias == null ? columnName : tableAlias + "." + columnName;
    }

    /**
     * Gets from table in sql.
     *
     * @return the from table in sql
     */
    protected String getFromTableInSQL() {
        String tableName = sqlRecognizer.getTableName();
        String tableAlias = sqlRecognizer.getTableAlias();
        return tableAlias == null ? tableName : tableName + " " + tableAlias;
    }

    /**
     * Gets table meta.
     *
     * @return the table meta
     */
    protected TableMeta getTableMeta() {
        return getTableMeta(sqlRecognizer.getTableName());
    }

    /**
     * Gets table meta.
     *
     * @param tableName the table name
     * @return the table meta
     */
    protected TableMeta getTableMeta(String tableName) {
        if (tableMeta != null) {
            return tableMeta;
        }
        if (JdbcConstants.ORACLE.equalsIgnoreCase(statementProxy.getConnectionProxy().getDbType())) {
            tableMeta = TableMetaCacheOracle.getTableMeta(statementProxy.getConnectionProxy().getDataSourceProxy(), tableName);
        } else {
            tableMeta = TableMetaCache.getTableMeta(statementProxy.getConnectionProxy().getDataSourceProxy(), tableName);
        }
        return tableMeta;
    }

    /**
     * prepare undo log.
     *
     * @param beforeImage the before image
     * @param afterImage  the after image
     * @throws SQLException the sql exception
     */
    protected void prepareUndoLog(TableRecords beforeImage, TableRecords afterImage) throws SQLException {
        if (beforeImage.getRows().size() == 0 && afterImage.getRows().size() == 0) {
            return;
        }

        ConnectionProxy connectionProxy = statementProxy.getConnectionProxy();

        TableRecords lockKeyRecords = sqlRecognizer.getSQLType() == SQLType.DELETE ? beforeImage : afterImage;
        String lockKeys = buildLockKey(lockKeyRecords);
        connectionProxy.appendLockKey(lockKeys);

        SQLUndoLog sqlUndoLog = buildUndoItem(beforeImage, afterImage);
        connectionProxy.appendUndoLog(sqlUndoLog);
    }

    /**
     * build lockKey
     *
     * @param rowsIncludingPK the records
     * @return the string
     */
    protected String buildLockKey(TableRecords rowsIncludingPK) {
        if (rowsIncludingPK.size() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(rowsIncludingPK.getTableMeta().getTableName());
        sb.append(":");
        int filedSequence = 0;
        for (Field field : rowsIncludingPK.pkRows()) {
            sb.append(field.getValue());
            filedSequence++;
            if (filedSequence < rowsIncludingPK.pkRows().size()) {
                sb.append(",");
            }
        }
        return sb.toString();
    }

    /**
     * build a SQLUndoLog
     *
     * @param beforeImage the before image
     * @param afterImage  the after image
     * @return sql undo log
     */
    protected SQLUndoLog buildUndoItem(TableRecords beforeImage, TableRecords afterImage) {
        SQLType sqlType = sqlRecognizer.getSQLType();
        String tableName = sqlRecognizer.getTableName();

        SQLUndoLog sqlUndoLog = new SQLUndoLog();
        sqlUndoLog.setSqlType(sqlType);
        sqlUndoLog.setTableName(tableName);
        sqlUndoLog.setBeforeImage(beforeImage);
        sqlUndoLog.setAfterImage(afterImage);
        return sqlUndoLog;
    }


    /**
     * build a BeforeImage
     *
     * @param tableMeta         the tableMeta
     * @param selectSQL         the selectSQL
     * @param paramAppenderList the paramAppender list
     * @return a tableRecords
     * @throws SQLException the sql exception
     */
    protected TableRecords buildTableRecords(TableMeta tableMeta, String selectSQL, ArrayList<List<Object>> paramAppenderList) throws SQLException {
        TableRecords tableRecords = null;
        PreparedStatement ps = null;
        Statement st = null;
        ResultSet rs = null;
        try {
            if (paramAppenderList.isEmpty()) {
                st = statementProxy.getConnection().createStatement();
                rs = st.executeQuery(selectSQL);
            } else {
                if (paramAppenderList.size() == 1) {
                    ps = statementProxy.getConnection().prepareStatement(selectSQL);
                    List<Object> paramAppender = paramAppenderList.get(0);
                    for (int i = 0; i < paramAppender.size(); i++) {
                        ps.setObject(i + 1, paramAppender.get(i));
                    }
                } else {
                    ps = statementProxy.getConnection().prepareStatement(selectSQL);
                    List<Object> paramAppender = null;
                    for (int i = 0; i < paramAppenderList.size(); i++) {
                        paramAppender = paramAppenderList.get(i);
                        for (int j = 0; j < paramAppender.size(); j++) {
                            ps.setObject(i * paramAppender.size() + j + 1, paramAppender.get(j));
                        }
                    }
                }
                rs = ps.executeQuery();
            }
            tableRecords = TableRecords.buildRecords(tableMeta, rs);
        } finally {
            if (rs != null) {
                rs.close();
            }
            if (st != null) {
                st.close();
            }
            if (ps != null) {
                ps.close();
            }
        }
        return tableRecords;
    }

    /**
     * build TableRecords
     *
     * @param pkValues the pkValues
     * @return return TableRecords;
     * @throws SQLException
     */
    protected TableRecords buildTableRecords(List<Object> pkValues) throws SQLException {
        TableRecords afterImage;
        String pk = getTableMeta().getPkName();
        StringJoiner pkValuesJoiner = new StringJoiner(" OR ", "SELECT * FROM " + getTableMeta().getTableName() + " WHERE ", "");
        for (Object pkValue : pkValues) {
            pkValuesJoiner.add(pk + "=?");
        }
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = statementProxy.getConnection().prepareStatement(pkValuesJoiner.toString());

            for (int i = 1; i <= pkValues.size(); i++) {
                ps.setObject(i, pkValues.get(i - 1));
            }

            rs = ps.executeQuery();
            afterImage = TableRecords.buildRecords(getTableMeta(), rs);

        } finally {
            if (rs != null) {
                rs.close();
            }
            if (ps != null) {
                ps.close();
            }
        }
        return afterImage;
    }

    /**
     * get db type
     * @return
     */
    protected String getDbType() {
        return statementProxy.getConnectionProxy().getDbType();
    }

}
