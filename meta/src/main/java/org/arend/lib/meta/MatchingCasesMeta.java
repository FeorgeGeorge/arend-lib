package org.arend.lib.meta;

import org.arend.ext.concrete.*;
import org.arend.ext.concrete.expr.*;
import org.arend.ext.core.body.CoreBody;
import org.arend.ext.core.body.CoreElimBody;
import org.arend.ext.core.body.CoreElimClause;
import org.arend.ext.core.body.CorePattern;
import org.arend.ext.core.context.CoreBinding;
import org.arend.ext.core.context.CoreParameter;
import org.arend.ext.core.definition.CoreDefinition;
import org.arend.ext.core.definition.CoreFunctionDefinition;
import org.arend.ext.core.expr.CoreCaseExpression;
import org.arend.ext.core.expr.CoreExpression;
import org.arend.ext.core.expr.CoreFunCallExpression;
import org.arend.ext.core.expr.UncheckedExpression;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.error.*;
import org.arend.ext.reference.ArendRef;
import org.arend.ext.reference.ExpressionResolver;
import org.arend.ext.typechecking.*;
import org.arend.lib.StdExtension;
import org.arend.lib.util.PatternUtils;
import org.arend.lib.util.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class MatchingCasesMeta extends BaseMetaDefinition implements MetaResolver {
  private final StdExtension ext;

  public MatchingCasesMeta(StdExtension ext) {
    this.ext = ext;
  }

  @Override
  public @Nullable boolean[] argumentExplicitness() {
    return new boolean[] { false, true, true };
  }

  @Override
  public int numberOfOptionalExplicitArguments() {
    return 1;
  }

  @Override
  public boolean requireExpectedType() {
    return true;
  }

  @Override
  public @Nullable ConcreteExpression resolvePrefix(@NotNull ExpressionResolver resolver, @NotNull ContextData contextData) {
    List<? extends ConcreteArgument> args = contextData.getArguments();
    if (args.size() > 2) {
      resolver.getErrorReporter().report(new NameResolverError("Expected at most 2 arguments", contextData.getMarker()));
      return null;
    }
    if (args.size() == 2 && !(!args.get(0).isExplicit() && args.get(1).isExplicit())) {
      resolver.getErrorReporter().report(new NameResolverError("Expected 1 implicit and 1 explicit argument", contextData.getMarker()));
      return null;
    }

    ConcreteFactory factory = ext.factory.withData(contextData.getMarker());
    ConcreteAppBuilder builder = factory.appBuilder(contextData.getReferenceExpression());
    if (!args.isEmpty() && !args.get(0).isExplicit()) {
      builder.app(resolver.resolve(args.get(0).getExpression()), args.get(0).isExplicit());
    }
    builder.app(resolver.resolve(factory.caseExpr(false, Collections.emptyList(), null, null, contextData.getClauses() == null ? Collections.emptyList() : contextData.getClauses().getClauseList())));
    if (args.size() == 1 && args.get(0).isExplicit()) {
      builder.app(resolver.resolve(args.get(0).getExpression()));
    } else if (args.size() > 1) {
      builder.app(resolver.resolve(args.get(1).getExpression()));
    }
    return builder.build();
  }

  @Override
  public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
    List<? extends ConcreteArgument> args = contextData.getArguments();
    ErrorReporter errorReporter = typechecker.getErrorReporter();
    ConcreteExpression marker = contextData.getMarker();
    ConcreteFactory factory = ext.factory.withData(marker);
    List<ArendRef> caseRefs = new ArrayList<>();
    List<ConcreteCaseArgument> caseArgs = new ArrayList<>();
    Deque<CoreExpression> subexpressions = new ArrayDeque<>();
    List<ConcreteParameter> types = new ArrayList<>();
    CoreExpression expectedType = contextData.getExpectedType().normalize(NormalizationMode.RNF);
    boolean[] isSCase = new boolean[] { false };
    List<List<CorePattern>> patterns = new ArrayList<>();

    // Parse parameters
    Set<Integer> caseOccurrences; // we are looking for \case expressions if caseOccurrences is either null or non-empty
    Map<CoreFunctionDefinition, Integer> defCount = new HashMap<>(); // if defCount.get(def) != null, then we are looking for def
    Map<CoreFunctionDefinition, Set<Integer>> defOccurrences = new HashMap<>(); // if we are looking for def and defOccurrences.get(def) == null, then we are looking for all occurrences; otherwise only for the specified ones; defOccurrences.get(def) is never empty
    if (!args.get(0).isExplicit()) {
      List<? extends ConcreteExpression> params = Utils.getArgumentList(args.get(0).getExpression());
      if (params.isEmpty()) {
        errorReporter.report(new IgnoredArgumentError(args.get(0).getExpression()));
        caseOccurrences = null;
      } else {
        List<List<? extends ConcreteExpression>> lists = new ArrayList<>();
        List<ConcreteExpression> defList = new ArrayList<>();
        for (ConcreteExpression param : params) {
          if (param instanceof ConcreteTupleExpression) {
            lists.add(Utils.getArgumentList(param));
          } else {
            defList.add(param);
          }
        }
        if (!defList.isEmpty()) {
          lists.add(defList);
        }

        Set<Integer> myCaseOccurrences = new HashSet<>();
        for (List<? extends ConcreteExpression> list : lists) {
          Set<Integer> occurrences = new HashSet<>();
          for (ConcreteExpression param : list) {
            if (param instanceof ConcreteNumberExpression) {
              occurrences.add(((ConcreteNumberExpression) param).getNumber().intValue());
            } else if (!(param instanceof ConcreteReferenceExpression)) {
              errorReporter.report(new TypecheckingError("Unrecognized parameter", param));
            }
          }
          boolean isCase = true;
          for (ConcreteExpression param : list) {
            if (param instanceof ConcreteReferenceExpression) {
              CoreDefinition def = ext.definitionProvider.getCoreDefinition(((ConcreteReferenceExpression) param).getReferent());
              if (def instanceof CoreFunctionDefinition && ((CoreFunctionDefinition) def).getBody() instanceof CoreElimBody) {
                isCase = false;
                if (defCount.putIfAbsent((CoreFunctionDefinition) def, 0) == null) {
                  if (!occurrences.isEmpty()) {
                    defOccurrences.put((CoreFunctionDefinition) def, occurrences);
                  }
                } else {
                  errorReporter.report(new IgnoredArgumentError("Parameters for '" + def.getName() + "' are already specified", param));
                }
              } else {
                errorReporter.report(new TypecheckingError("Expected a function defined by pattern matching", param));
              }
            }
          }
          if (isCase) {
            if (myCaseOccurrences != null && myCaseOccurrences.isEmpty()) {
              myCaseOccurrences = occurrences.isEmpty() ? null : occurrences;
            } else {
              errorReporter.report(new IgnoredArgumentError("Parameters for \\case expressions are already specified", list.isEmpty() ? args.get(0).getExpression() : list.get(0)));
            }
          }
        }
        caseOccurrences = myCaseOccurrences;
      }
    } else {
      caseOccurrences = null;
    }

    // Find subexpressions
    int[] caseCount = { 0 };
    expectedType.findSubexpression(expr -> {
      Collection<? extends CoreExpression> matchArgs = null;
      List<? extends CoreElimClause> clauses = null;
      if (expr instanceof CoreCaseExpression && (caseOccurrences == null || caseOccurrences.remove(++caseCount[0]))) {
        CoreCaseExpression caseExpr = (CoreCaseExpression) expr;
        if (caseExpr.isSCase()) {
          isSCase[0] = true;
        }
        matchArgs = caseExpr.getArguments();
        clauses = caseExpr.getElimBody().getClauses();
      } else if (expr instanceof CoreFunCallExpression) {
        CoreFunctionDefinition def = ((CoreFunCallExpression) expr).getDefinition();
        Integer count = defCount.get(def);
        if (count != null) {
          Set<Integer> occurrences = defOccurrences.get(def);
          if (occurrences == null || occurrences.contains(count + 1)) {
            CoreBody body = def.getBody();
            if (body instanceof CoreElimBody) {
              if (def.getKind() == CoreFunctionDefinition.Kind.SFUNC) {
                isSCase[0] = true;
              }
              matchArgs = ((CoreFunCallExpression) expr).getDefCallArguments();
              clauses = ((CoreElimBody) body).getClauses();
            }
          }
          if (occurrences != null) {
            defCount.put(def, count + 1);
          }
        }
      }

      if (matchArgs != null) {
        for (CoreExpression argument : matchArgs) {
          if (!(argument instanceof CoreCaseExpression) || caseOccurrences != null) {
            subexpressions.add(argument);
            TypedExpression typed = argument.computeTyped();
            ArendRef ref = factory.local(ext.renamerFactory.getNameFromType(typed.getType(), null));
            caseRefs.add(ref);
            caseArgs.add(factory.caseArg(factory.core(typed), ref, null));
            types.add(factory.param(Collections.emptyList(), factory.core(typed.getType().computeTyped())));
          }
        }

        if (!clauses.isEmpty()) {
          int s = matchArgs.size();
          for (int i = 0; i < s; i++) {
            patterns.add(new ArrayList<>());
          }
          for (CoreElimClause clause : clauses) {
            List<? extends CorePattern> clausePatterns = clause.getPatterns();
            assert clausePatterns.size() == s;
            for (int i = 0; i < s; i++) {
              patterns.get(patterns.size() - (s - i)).add(clausePatterns.get(i));
            }
          }
        }

        return caseOccurrences != null && caseOccurrences.isEmpty() && defCount.isEmpty();
      }

      return false;
    });

    if (subexpressions.isEmpty()) {
      errorReporter.report(new TypecheckingError("Cannot find matching subexpressions", contextData.getMarker()));
      return null;
    }

    CoreParameter caseParams = typechecker.typecheckParameters(types);
    if (caseParams == null) {
      return null;
    }

    int caseParam = args.get(0).isExplicit() ? 0 : 1;
    List<? extends ConcreteClause> actualClauses = ((ConcreteCaseExpression) args.get(caseParam).getExpression()).getClauses();
    List<List<CorePattern>> actualPatterns;
    if (!actualClauses.isEmpty()) {
      boolean ok = true;
      int s = Utils.parametersSize(caseParams);
      actualPatterns = new ArrayList<>();
      for (ConcreteClause clause : actualClauses) {
        if (clause.getPatterns().size() != s) {
          errorReporter.report(new TypecheckingError("Expected " + s + " patterns", clause));
          ok = false;
          continue;
        }
        List<CorePattern> row = typechecker.typecheckPatterns(clause.getPatterns(), caseParams, args.get(caseParam).getExpression());
        if (row == null) {
          ok = false;
          continue;
        }
        actualPatterns.add(row);
      }
      if (!ok) {
        return null;
      }
    } else {
      actualPatterns = Collections.emptyList();
    }

    List<List<CorePattern>> expectedPatterns = deletePatterns(product(patterns), actualPatterns);
    List<? extends ConcreteClause> finalClauses = actualClauses;
    if (caseParam + 1 < args.size()) {
      ConcreteExpression def = args.get(caseParam + 1).getExpression();
      if (expectedPatterns.isEmpty()) {
        errorReporter.report(new IgnoredArgumentError(def));
      } else {
        List<ConcreteClause> clauses = new ArrayList<>(actualClauses);
        for (List<CorePattern> row : expectedPatterns) {
          List<ConcretePattern> concretePatterns = new ArrayList<>();
          for (CorePattern pattern : row) {
            concretePatterns.add(PatternUtils.toConcrete(pattern, ext.renamerFactory, factory));
          }
          clauses.add(factory.clause(concretePatterns, def));
        }
        finalClauses = clauses;
      }
    } else if (!expectedPatterns.isEmpty()) {
      errorReporter.report(new MissingClausesError(expectedPatterns, caseParams, Collections.emptyList(), false, marker));
      return null;
    }

    return typechecker.typecheck(factory.caseExpr(isSCase[0], caseArgs, factory.meta("case_return", new MetaDefinition() {
      @Override
      public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
        UncheckedExpression result = expectedType.replaceSubexpressions(expr -> {
          if (!subexpressions.isEmpty() && subexpressions.getFirst() == expr) {
            CoreBinding binding = typechecker.getFreeBinding(caseRefs.get(caseRefs.size() - subexpressions.size()));
            subexpressions.removeFirst();
            return binding == null ? null : binding.makeReference();
          }
          return null;
        });
        if (result == null) {
          errorReporter.report(new TypecheckingError("Cannot substitute expressions", marker));
          return null;
        }
        return typechecker.check(result, marker);
      }
    }), null, finalClauses), contextData.getExpectedType());
  }

  private List<List<CorePattern>> product(List<List<CorePattern>> patterns) {
    List<List<CorePattern>> result = Collections.singletonList(Collections.emptyList());
    for (List<CorePattern> column : patterns) {
      List<List<CorePattern>> newResult = new ArrayList<>();
      for (List<CorePattern> list : result) {
        for (CorePattern pattern : column) {
          List<CorePattern> newList = new ArrayList<>(list.size() + 1);
          newList.addAll(list);
          newList.add(pattern);
          newResult.add(newList);
        }
      }
      result = newResult;
    }

    return result;
  }

  private List<List<CorePattern>> deletePatterns(List<List<CorePattern>> expectedPatterns, List<List<CorePattern>> actualPatterns) {
    if (actualPatterns.isEmpty()) {
      return expectedPatterns;
    }

    List<List<CorePattern>> result = new ArrayList<>();
    for (List<CorePattern> expectedRow : expectedPatterns) {
      boolean take = true;
      for (List<CorePattern> actualRow : actualPatterns) {
        if (PatternUtils.refines(expectedRow, actualRow)) {
          take = false;
          break;
        }
      }
      if (take) {
        result.add(expectedRow);
      }
    }

    return result;
  }
}
