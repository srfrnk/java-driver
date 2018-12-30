/*
 * Copyright DataStax, Inc.
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
package com.datastax.driver.mapping;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.GuavaCompatibility;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TypeCodec;
import com.datastax.driver.mapping.annotations.Defaults;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Set;

class MethodMapper {

  final Method method;
  final String queryString;
  private final ParamMapper[] paramMappers;

  private final ConsistencyLevel consistency;
  private final int fetchSize;
  private final boolean tracing;
  private final Boolean idempotent;

  private Session session;
  private PreparedStatement statement;

  private boolean returnStatement;
  private Mapper<?> returnMapper;
  private boolean mapOne;
  private boolean async;

  MethodMapper(
      Method method,
      String queryString,
      ParamMapper[] paramMappers,
      ConsistencyLevel consistency,
      int fetchSize,
      boolean enableTracing,
      Boolean idempotent) {
    this.method = method;
    this.queryString = queryString;
    this.paramMappers = paramMappers;
    this.consistency = consistency;
    this.fetchSize = fetchSize;
    this.tracing = enableTracing;
    this.idempotent = idempotent;
  }

  public void prepare(MappingManager manager, PreparedStatement ps) {
    this.session = manager.getSession();
    this.statement = ps;

    validateParameters();

    Class<?> returnType = method.getReturnType();
    if (Void.TYPE.isAssignableFrom(returnType) || ResultSet.class.isAssignableFrom(returnType))
      return;

    if (Statement.class.isAssignableFrom(returnType)) {
      returnStatement = true;
      return;
    }

    if (ResultSetFuture.class.isAssignableFrom(returnType)) {
      this.async = true;
      return;
    }

    if (ListenableFuture.class.isAssignableFrom(returnType)) {
      this.async = true;
      Type k = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
      if (k instanceof Class && ResultSet.class.isAssignableFrom((Class<?>) k)) return;

      mapType(manager, returnType, k);
    } else {
      mapType(manager, returnType, method.getGenericReturnType());
    }
  }

  // Checks the method parameters against the query's bind variables
  private void validateParameters() {
    if (method.isVarArgs())
      throw new IllegalArgumentException(
          String.format("Invalid varargs method %s in @Accessor interface", method.getName()));

    ColumnDefinitions variables = statement.getVariables();
    Set<String> names = Sets.newHashSet();
    for (ColumnDefinitions.Definition variable : variables) {
      names.add(variable.getName());
    }

    if (method.getParameterTypes().length < names.size())
      throw new IllegalArgumentException(
          String.format(
              "Not enough arguments for method %s, "
                  + "found %d but it should be at least the number of unique bind parameter names in the @Query (%d)",
              method.getName(), method.getParameterTypes().length, names.size()));

    if (method.getParameterTypes().length > variables.size())
      throw new IllegalArgumentException(
          String.format(
              "Too many arguments for method %s, "
                  + "found %d but it should be at most the number of bind parameters in the @Query (%d)",
              method.getName(), method.getParameterTypes().length, variables.size()));

    // TODO could go further, e.g. check that the types match, inspect @Param annotations to check
    // that all names are bound...
  }

  @SuppressWarnings("rawtypes")
  private void mapType(MappingManager manager, Class<?> fullReturnType, Type type) {

    if (type instanceof ParameterizedType) {
      ParameterizedType pt = (ParameterizedType) type;
      Type raw = pt.getRawType();
      if (raw instanceof Class && Result.class.isAssignableFrom((Class) raw)) {
        type = pt.getActualTypeArguments()[0];
      } else {
        mapOne = true;
      }
    } else {
      mapOne = true;
    }

    if (!(type instanceof Class))
      throw new RuntimeException(
          String.format("Cannot map return of method %s to unsupported type %s", method, type));

    try {
      this.returnMapper = manager.mapper((Class<?>) type);
    } catch (Exception e) {
      throw new RuntimeException("Cannot map return to class " + fullReturnType, e);
    }
  }

  Object invoke(Object[] args) {

    BoundStatement bs = statement.bind();

    for (int i = 0; i < args.length; i++) {
      paramMappers[i].setValue(bs, args[i]);
    }

    if (consistency != null) bs.setConsistencyLevel(consistency);
    if (fetchSize > 0) bs.setFetchSize(fetchSize);
    if (tracing) bs.enableTracing();
    if (idempotent != null) bs.setIdempotent(idempotent);

    if (returnStatement) return bs;

    if (async) {
      ListenableFuture<ResultSet> future = session.executeAsync(bs);
      if (returnMapper == null) return future;

      return mapOne
          ? Futures.transform(future, returnMapper.mapOneFunctionWithoutAliases)
          : Futures.transform(
              future, returnMapper.mapAllFunctionWithoutAliases);
    } else {
      ResultSet rs = session.execute(bs);
      if (returnMapper == null) return rs;

      Result<?> result = returnMapper.map(rs);
      return mapOne ? result.one() : result;
    }
  }

  static class ParamMapper {
    // We'll only set one or the other. If paramName is null, then paramIdx is used.
    private final String paramName;
    private final int paramIdx;
    private final TypeToken<Object> paramType;
    private final TypeCodec<Object> codec;

    @SuppressWarnings("unchecked")
    ParamMapper(
        String paramName,
        int paramIdx,
        TypeToken<?> paramType,
        Class<? extends TypeCodec<?>> codecClass) {
      this.paramName = paramName;
      this.paramIdx = paramIdx;
      this.paramType = (TypeToken<Object>) paramType;
      this.codec =
          codecClass == null || codecClass.equals(Defaults.NoCodec.class)
              ? null
              : (TypeCodec<Object>) ReflectionUtils.newInstance(codecClass);
    }

    void setValue(BoundStatement boundStatement, Object arg) {
      if (paramName == null) {
        if (codec == null) boundStatement.set(paramIdx, arg, paramType);
        else boundStatement.set(paramIdx, arg, codec);
      } else {
        if (codec == null) boundStatement.set(paramName, arg, paramType);
        else boundStatement.set(paramName, arg, codec);
      }
    }
  }
}
