/*
 * Copyright 2019 Immutables Authors and Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.immutables.criteria.inmemory;

import com.google.common.base.Preconditions;
import org.immutables.criteria.expression.Call;
import org.immutables.criteria.expression.Constant;
import org.immutables.criteria.expression.Expression;
import org.immutables.criteria.expression.ExpressionVisitor;
import org.immutables.criteria.expression.Expressions;
import org.immutables.criteria.expression.Operator;
import org.immutables.criteria.expression.Operators;
import org.immutables.criteria.expression.Path;
import org.immutables.criteria.expression.Query;

import javax.annotation.Nullable;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Evaluator (predicate) based on reflection. Uses expression visitor API to construct the predicate.
 *
 * <p>Probably most useful in testing scenarios</p>
 */
public class InMemoryExpressionEvaluator<T> implements Predicate<T> {

  /**
   * Sentinel used for Three-Valued Logic: true / false / unknown
   */
  private static final Object UNKNOWN = new Object() {
    @Override
    public String toString() {
      return "UNKNOWN";
    }
  };

  private final Expression expression;

  private InMemoryExpressionEvaluator(Expression expression) {
    this.expression = Objects.requireNonNull(expression, "expression");
  }

  /**
   * Factory method to create evaluator instance
   */
  public static <T> Predicate<T> of(Expression expression) {
    return Expressions.extractPredicate(expression)
            .<Predicate<T>>map(InMemoryExpressionEvaluator::new)
            .orElse(e -> Boolean.TRUE);
  }

  @Override
  public boolean test(T instance) {
    Objects.requireNonNull(instance, "instance");
    final LocalVisitor visitor = new LocalVisitor(instance);
    return Boolean.TRUE.equals(expression.accept(visitor));
  }

  private static class LocalVisitor implements ExpressionVisitor<Object> {

    private final ValueExtractor<Object> extractor;

    private LocalVisitor(Object instance) {
      this.extractor = new ReflectionFieldExtractor<>(instance);
    }

    @Override
    public Object visit(Call call) {
      final Operator op = call.operator();
      final List<Expression> args = call.arguments();

      if (op == Operators.EQUAL || op == Operators.NOT_EQUAL) {
        Preconditions.checkArgument(args.size() == 2, "Size should be 2 for %s but was %s", op, args.size());
        final Object left = args.get(0).accept(this);
        final Object right = args.get(1).accept(this);

        if (left == UNKNOWN || right == UNKNOWN) {
          return UNKNOWN;
        }

        final boolean equals = Objects.equals(left, right);
        return (op == Operators.EQUAL) == equals;
      }

      if (op == Operators.IN || op == Operators.NOT_IN) {
        Preconditions.checkArgument(args.size() == 2, "Size should be 2 for %s but was %s", op, args.size());
        final Object left = args.get(0).accept(this);
        if (left == UNKNOWN) {
          return UNKNOWN;
        }
        @SuppressWarnings("unchecked")
        final Iterable<Object> right = (Iterable<Object>) args.get(1).accept(this);
        Preconditions.checkNotNull(right, "not expected to be null %s", args.get(1));
        final Stream<Object> stream = StreamSupport.stream(right.spliterator(), false);

        return op == Operators.IN ? stream.anyMatch(r -> Objects.equals(left, r)) : stream.noneMatch(r -> Objects.equals(left, r));
      }

      if (op == Operators.IS_ABSENT || op == Operators.IS_PRESENT) {
        Preconditions.checkArgument(args.size() == 1, "Size should be 1 for %s but was %s", op, args.size());
        final Object left = args.get(0).accept(this);

        if (left instanceof java.util.Optional) {
          Optional<?> opt = (java.util.Optional<?>) left;
          return (op == Operators.IS_ABSENT) != opt.isPresent();
        }

        if (left instanceof com.google.common.base.Optional) {
          // guava Optional
          com.google.common.base.Optional<?> opt = (com.google.common.base.Optional<?>) left;
          return (op == Operators.IS_ABSENT) != opt.isPresent();
        }

        if (left == UNKNOWN) {
         return (op == Operators.IS_ABSENT);
        }

        return (op == Operators.IS_ABSENT) ? Objects.isNull(left) : Objects.nonNull(left);
      }

      if (op == Operators.AND || op == Operators.OR) {
        Preconditions.checkArgument(!args.isEmpty(), "empty args for %s", op);
        final boolean shortCircuit = op == Operators.OR;
        boolean prev = !shortCircuit;
        for (Expression exp:args) {
          final Object result = exp.accept(this);
          if (result == null || result == UNKNOWN) {
            return UNKNOWN;
          } else if (prev == shortCircuit || Objects.equals(shortCircuit, result)) {
            return shortCircuit;
          } else {
            // continue evaluating
            prev = (Boolean) result;
          }
        }

        return prev;
      }

      // comparables
      if (Arrays.asList(Operators.GREATER_THAN, Operators.GREATER_THAN_OR_EQUAL,
              Operators.LESS_THAN, Operators.LESS_THAN_OR_EQUAL).contains(op)) {

        Preconditions.checkArgument(args.size() == 2, "Size should be 2 for %s but was %s", op, args.size());

        @SuppressWarnings("unchecked")
        final Comparable<Object> left = (Comparable<Object>) args.get(0).accept(this);
        if (left == UNKNOWN || left == null) {
          return UNKNOWN;
        }
        @SuppressWarnings("unchecked")
        final Comparable<Object> right = (Comparable<Object>) args.get(1).accept(this);
        if (right == UNKNOWN || right == null) {
          return UNKNOWN;
        }

        final int compare = left.compareTo(right);

        if (op == Operators.GREATER_THAN) {
          return compare > 0;
        } else if (op == Operators.GREATER_THAN_OR_EQUAL) {
          return compare >= 0;
        } else if (op == Operators.LESS_THAN) {
          return compare < 0;
        } else if (op == Operators.LESS_THAN_OR_EQUAL) {
          return compare <= 0;
        }
      }

      throw new UnsupportedOperationException("Don't know how to handle " + op);
    }

    @Override
    public Object visit(Constant constant) {
      return constant.value();
    }

    @Override
    public Object visit(Path path) {
      final Object extracted = extractor.extract(path);

      // unwrap optional
      if (extracted instanceof java.util.Optional) {
        return ((java.util.Optional) extracted).orElse(UNKNOWN);
      }

      if (extracted instanceof com.google.common.base.Optional) {
        return ((com.google.common.base.Optional) extracted).or(UNKNOWN);
      }

      return extracted;
    }

    @Override
    public Object visit(Query query) {
      return query.filter().map(e -> e.accept(this)).orElse(Boolean.TRUE);
    }
  }

  private interface ValueExtractor<T> {
    @Nullable
    Object extract(Path path);
  }

  private static class ReflectionFieldExtractor<T> implements ValueExtractor<T> {
    private final T object;

    private ReflectionFieldExtractor(T object) {
      this.object = object;
    }

    @Nullable
    @Override
    public Object extract(Path path) {
      Objects.requireNonNull(path, "path");

      Object result = object;

      for (AnnotatedElement member: path.paths()) {
        result = extract(result, (Member) member);
        if (result == UNKNOWN) {
          break;
        }
      }

      return result;
    }

    private static Object extract(Object instance, Member member) {
      if (instance == null) {
        return UNKNOWN;
      }

      try {
        // TODO caching
        final Object result;
        if (member instanceof Method) {
          final Method method = (Method) member;
          if (!method.isAccessible()) {
            method.setAccessible(true);
          }
          result = method.invoke(instance);
        } else if (member instanceof Field) {
          Field field = (Field) member;
          if (!field.isAccessible()) {
            field.setAccessible(true);
          }
          result = field.get(instance);
        } else {
          throw new IllegalArgumentException(String.format("%s is not field or method", member));
        }

        return result;
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }

  }

}
