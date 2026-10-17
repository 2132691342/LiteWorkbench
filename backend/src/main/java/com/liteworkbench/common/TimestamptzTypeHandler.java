package com.liteworkbench.common;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;

/** LocalDateTime 与 timestamptz 列的双向映射：默认处理器走 getObject(LocalDateTime.class)，PostgreSQL JDBC 对 timestamptz 直接拒绝，这里统一改走 Timestamp 通道 */
@MappedTypes(LocalDateTime.class)
public class TimestamptzTypeHandler extends BaseTypeHandler<LocalDateTime> {

    /** 写入：本地时间转瞬时 */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setTimestamp(i, Timestamp.valueOf(parameter));
    }

    /** 读回：按列名 */
    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Timestamp value = rs.getTimestamp(columnName);
        return Objects.isNull(value) ? null : value.toLocalDateTime();
    }

    /** 读回：按列序 */
    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Timestamp value = rs.getTimestamp(columnIndex);
        return Objects.isNull(value) ? null : value.toLocalDateTime();
    }

    /** 读回：存储过程出参 */
    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Timestamp value = cs.getTimestamp(columnIndex);
        return Objects.isNull(value) ? null : value.toLocalDateTime();
    }
}
