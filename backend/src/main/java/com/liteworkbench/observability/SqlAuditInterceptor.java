package com.liteworkbench.observability;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.config.ObserveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/** SQL 审计：拦截已编译语句的执行，记录语句、入参、影响行数与耗时，并按阈值把慢查询升级为 warn */
@Slf4j
@Component
@RequiredArgsConstructor
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
public class SqlAuditInterceptor implements Interceptor {

    private final ObserveProperties observeProperties;
    private final ObserveStats observeStats;

    @Override
    /** 拦截语句执行，记录语句、入参、影响行数与耗时 */
    public Object intercept(Invocation invocation) throws Throwable {
        if (!observeProperties.getSql().isEnabled()) {
            return invocation.proceed();
        }
        Object handler = unwrap(invocation.getTarget());
        BoundSql boundSql = (BoundSql) meta(handler).getValue("boundSql");
        MappedStatement statement = (MappedStatement) meta(handler).getValue("mappedStatement");
        String method = methodOf(statement);
        // 观测键用未截断的语句：截断会把两条长语句的前缀合成一个键，统计就失真
        String key = compress(boundSql.getSql(), Integer.MAX_VALUE);
        String sql = StrUtil.sub(key, 0, observeProperties.getSql().getMaxSqlChars());
        String params = params(boundSql, statement);
        long start = System.currentTimeMillis();
        try {
            Object result = invocation.proceed();
            record(key, method, sql, params, rows(result), System.currentTimeMillis() - start, null);
            return result;
        } catch (Throwable error) {
            record(key, method, sql, params, -1, System.currentTimeMillis() - start, error);
            throw error;
        }
    }

    private void record(String key, String method, String sql, String params, long rows, long cost, Throwable error) {
        if (Objects.isNull(error)) {
            if (cost >= observeProperties.getSql().getSlowMs()) {
                log.warn("[sql.slow] method={} cost={}ms threshold={}ms rows={} sql={} params={}",
                        method, cost, observeProperties.getSql().getSlowMs(), rows, sql, params);
            } else {
                log.info("[sql.exec] method={} cost={}ms rows={} sql={} params={}", method, cost, rows, sql, params);
            }
            observeStats.recordSql(key, cost, method);
            return;
        }
        log.warn("[sql.fail] method={} cost={}ms sql={} params={} err={}", method, cost, sql, params, error.getMessage());
    }

    /** mapper 方法短名：剥掉包前缀，保留 类名.方法名 */
    private String methodOf(MappedStatement statement) {
        String id = statement.getId();
        int lastDot = id.lastIndexOf('.');
        int prevDot = lastDot < 1 ? -1 : id.lastIndexOf('.', lastDot - 1);
        return prevDot < 0 ? id : id.substring(prevDot + 1);
    }

    /** RoutingStatementHandler 把真正的处理器藏在 delegate 里，两种形态都要能取到属性 */
    private Object unwrap(Object target) {
        MetaObject meta = meta(target);
        return meta.hasGetter("delegate") ? meta.getValue("delegate") : target;
    }

    private MetaObject meta(Object target) {
        return SystemMetaObject.forObject(target);
    }

    /** 影响行数：查询取结果条数，写操作取驱动返回值，其余记为 -1 */
    private long rows(Object result) {
        if (result instanceof List<?> list) {
            return list.size();
        }
        if (result instanceof Number number) {
            return number.longValue();
        }
        return -1L;
    }

    /** 入参摘要：逐参数输出 属性=值，敏感名整值替换为 ***，取不到属性时回退参数对象本身 */
    private String params(BoundSql boundSql, MappedStatement statement) {
        Object paramObject = boundSql.getParameterObject();
        if (Objects.isNull(paramObject)) {
            return "";
        }
        List<ParameterMapping> mappings = boundSql.getParameterMappings();
        if (mappings.isEmpty()) {
            return abbrev(paramObject);
        }
        MetaObject meta = statement.getConfiguration().newMetaObject(paramObject);
        StringBuilder text = new StringBuilder(64);
        for (ParameterMapping mapping : mappings) {
            if (text.length() > 0) {
                text.append(", ");
            }
            String property = mapping.getProperty();
            text.append(property).append('=');
            if (LogSanitizer.isSensitive(property, observeProperties.getSensitiveKeys())) {
                text.append(LogSanitizer.MASK);
                continue;
            }
            try {
                text.append(abbrev(meta.getValue(property)));
            } catch (RuntimeException e) {
                text.append(abbrev(paramObject));
            }
        }
        return StrUtil.sub(text.toString(), 0, observeProperties.getSql().getMaxParamChars());
    }

    /** 语句归一化：压掉换行与连续空白，便于聚合时同一模板落到同一个键 */
    private String compress(String sql, int maxChars) {
        return StrUtil.sub(sql.replaceAll("\\s+", " ").trim(), 0, maxChars);
    }

    private String abbrev(Object value) {
        return StrUtil.sub(String.valueOf(value), 0, 80);
    }
}
