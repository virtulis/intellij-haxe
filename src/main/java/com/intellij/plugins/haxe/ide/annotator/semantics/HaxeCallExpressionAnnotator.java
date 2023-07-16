package com.intellij.plugins.haxe.ide.annotator.semantics;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeMethodDeclarationImpl;
import com.intellij.plugins.haxe.model.*;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.plugins.haxe.util.UsefulPsiTreeUtil;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.intellij.plugins.haxe.ide.annotator.semantics.TypeParameterUtil.*;
import static com.intellij.plugins.haxe.model.type.HaxeTypeCompatible.canAssignToFrom;
import static com.intellij.plugins.haxe.model.type.HaxeTypeCompatible.getUnderlyingClassIfAbstractNull;
import static com.intellij.plugins.haxe.model.type.HaxeTypeResolver.getTypeFromFunctionArgument;

public class HaxeCallExpressionAnnotator implements Annotator {
  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    if (element instanceof HaxeCallExpression expression) {
      if (expression.getExpression() instanceof HaxeReference reference) {
        final PsiElement resolved = reference.resolve();
        if (resolved instanceof HaxePsiField field) {
          checkFunctionCall(expression, field, holder);
        }
        else if (resolved instanceof HaxeMethod method) {
          checkMethodCall(expression, method, holder);
        }
      }
    }
    if (element instanceof HaxeNewExpression newExpression) {
      checkConstructor(newExpression, holder);
    }
  }

  private void checkFunctionCall(HaxeCallExpression callExpression, HaxePsiField resolvedField, AnnotationHolder holder) {

    HaxeResolveResult result = HaxeResolveUtil.getHaxeClassResolveResult(resolvedField);
    HaxeClass haxeClass = result.getHaxeClass();

    if (haxeClass instanceof HaxeSpecificFunction specificFunction) {
      HaxeFunctionType functionType = specificFunction.getFunctionType();
      HaxeFunctionTypeModel model = new HaxeFunctionTypeModel(functionType);

      HaxeExpression methodExpression = callExpression.getExpression();

      HaxeCallExpressionList callExpressionList = callExpression.getExpressionList();
      List<HaxeFunctionTypeParameterModel> parameterList = model.getParameters();
      List<HaxeExpression> argumentList = Optional.ofNullable(callExpressionList)
        .map(HaxeExpressionList::getExpressionList)
        .orElse(List.of());

      boolean hasVarArgs = parameterList.stream().anyMatch(HaxeFunctionTypeParameterModel::isRestArgument);
      long minArgRequired = countRequiredFunctionTypeArguments(parameterList);
      long maxArgAllowed = hasVarArgs ? Long.MAX_VALUE : parameterList.size();

      // min arg check

      if (argumentList.size() < minArgRequired) {
        String message = HaxeBundle.message("haxe.semantic.method.parameter.missing", minArgRequired, argumentList.size());
        if (argumentList.isEmpty()) {
          PsiElement first = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(methodExpression);
          PsiElement second = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(first);
          TextRange range = TextRange.create(first.getTextOffset(), second.getTextOffset() + 1);
          holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create();
        }
        else {
          holder.newAnnotation(HighlightSeverity.ERROR, message).range(callExpressionList).create();
        }
        return;
      }
      //max arg check
      if (argumentList.size() > maxArgAllowed) {
        String message = HaxeBundle.message("haxe.semantic.method.parameter.too.many", maxArgAllowed, argumentList.size());
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(callExpressionList).create();
        return;
      }

      // generics and type parameter
      HaxeGenericSpecialization specialization = specificFunction.getSpecialization();
      HaxeGenericResolver resolver = findGenericResolverFromVariable(callExpression.getExpression());

      if (resolver == null && specialization != null) {
        resolver = specialization.toGenericResolver(callExpression);
      }
      if (resolver == null) resolver = new HaxeGenericResolver();


      int parameterCounter = 0;
      int argumentCounter = 0;

      boolean isRestArg = false;
      HaxeFunctionTypeParameterModel parameter = null;
      HaxeExpression argument;

      ResultHolder parameterType = null;
      ResultHolder argumentType = null;

      // checking arguments is a bit complicated, rest parameters allow "infinite" arguments and optional parameters can be "skipped"
      // so we only want to break the loop once we have either exhausted the arguments or parameter list.
      while (true) {
        HaxeGenericResolver localResolver = new HaxeGenericResolver();
        localResolver.addAll(resolver);

        if (argumentList.size() > argumentCounter) {
          argument = argumentList.get(argumentCounter++);
        }
        else {
          // out of arguments
          break;
        }

        if (!isRestArg) {
          if (parameterList.size() > parameterCounter) {
            parameter = parameterList.get(parameterCounter++);
            if (parameter.isRestArgument()) isRestArg = true;
          }
          else {
            // out of parameters and last is not var arg, must mean that ve have skipped optionals and still had arguments left
            if (parameterType != null && argumentType != null) {
              annotateTypeMismatch(holder, parameterType, argumentType, argument);
            }
            break;
          }
        }

        argumentType = resolveArgumentType(argument, localResolver);
        parameterType = parameter.getArgumentType();


        //TODO properly resolve typedefs
        SpecificHaxeClassReference argumentClass = argumentType.getClassType();
        if (argumentClass != null && argumentClass.isFunction() && parameterType.isTypeDef()) {
          // make sure that if  parameter type is typedef  try to convert to function so we can compare with argument
          parameterType = resolveParameterType(parameterType, argumentClass);
        }


        // check if  argument matches Type Parameter constraint
        ResultHolder resolvedParameterType = HaxeTypeResolver.resolveParameterizedType(parameterType, resolver);
        if (!canAssignToFrom(resolvedParameterType, argumentType)) {
          if (parameter.isOptional()) {
            argumentCounter--; //retry argument with next parameter
          }
          else {
            annotateTypeMismatch(holder, resolvedParameterType, argumentType, argument);
          }
        }
      }
    }
  }


  private void checkConstructor(HaxeNewExpression resolvedNewExpression, AnnotationHolder holder) {
    ResultHolder type = HaxeTypeResolver.getTypeFromType(resolvedNewExpression.getType());
    // ignore anything where we dont have class model
    if (type.missingClassModel()) {
      return;
    }
    HaxeMethodModel constructor = type.getClassType().getHaxeClass().getModel().getConstructor(null);

    // if we cant find a constructor  ignore
    // TODO (might add a missing constructor  annotation here)
    if (constructor == null) {
      return;
    }


    if (constructor.isOverload()) {
      //TODO implement support for overloaded methods (need to get correct model ?)
      return; //(stopping here to avoid marking arguments as type mismatch)
    }


    List<HaxeParameterModel> parameterList = constructor.getParameters();
    List<HaxeExpression> argumentList = resolvedNewExpression.getExpressionList();


    boolean hasVarArgs = hasVarArgs(parameterList);
    long minArgRequired = countRequiredArguments(parameterList);
    long maxArgAllowed = hasVarArgs ? Long.MAX_VALUE : parameterList.size();

    // min arg check

    if (argumentList.size() < minArgRequired) {
      String message = HaxeBundle.message("haxe.semantic.method.parameter.missing", minArgRequired, argumentList.size());
      if (argumentList.isEmpty()) {
        PsiElement first = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(resolvedNewExpression);
        PsiElement second = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(first);
        TextRange range = TextRange.create(first.getTextOffset(), second.getTextOffset() + 1);
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create();
      }
      else {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(resolvedNewExpression).create();
      }
      return;
    }
    //max arg check
    if (argumentList.size() > maxArgAllowed) {
      String message = HaxeBundle.message("haxe.semantic.method.parameter.too.many", maxArgAllowed, argumentList.size());
      holder.newAnnotation(HighlightSeverity.ERROR, message).range(resolvedNewExpression).create();
      return;
    }

    // generics and type parameter
    HaxeGenericSpecialization specialization = resolvedNewExpression.getSpecialization();
    HaxeGenericResolver resolver = findGenericResolverFromVariable(resolvedNewExpression);

    if (resolver == null && specialization != null) {
      resolver = specialization.toGenericResolver(resolvedNewExpression);
    }
    if (resolver == null) resolver = new HaxeGenericResolver();

    Map<String, ResultHolder> typeParamMap = createTypeParameterConstraintMap(constructor.getMethod(), resolver);


    int parameterCounter = 0;
    int argumentCounter = 0;


    boolean isRestArg = false;
    HaxeParameterModel parameter = null;
    HaxeExpression argument;

    ResultHolder parameterType;
    ResultHolder argumentType;

    // checking arguments is a bit complicated, rest parameters allow "infinite" arguments and optional parameters can be "skipped"
    // so we only want to break the loop once we have either exhausted the arguments or parameter list.
    while (true) {
      HaxeGenericResolver localResolver = new HaxeGenericResolver();
      localResolver.addAll(resolver);

      if (argumentList.size() > argumentCounter) {
        argument = argumentList.get(argumentCounter++);
      }
      else {
        // out of arguments
        break;
      }

      if (!isRestArg) {
        if (parameterList.size() > parameterCounter) {
          parameter = parameterList.get(parameterCounter++);
          if (isVarArg(parameter)) isRestArg = true;
        }
        else {
          // out of parameters and last is not var arg
          break;
        }
      }

      localResolver.addAll(constructor.getGenericResolver(null).withoutUnknowns());

      argumentType = resolveArgumentType(argument, localResolver);
      parameterType = resolveParameterType(parameter, localResolver);

      // when methods has type-parameters we can inherit the type from arguments (note that they may contain constraints)
      if (containsTypeParameter(parameterType, typeParamMap)) {
        inheritTypeParametersFromArgument(parameterType, argumentType, resolver, typeParamMap);
        // attempt re-resolve after adding inherited type parameters
        parameterType = resolveParameterType(parameter, resolver);
      }

      //TODO properly resolve typedefs
      SpecificHaxeClassReference argumentClass = argumentType.getClassType();
      if (argumentClass != null && argumentClass.isFunction() && parameterType.isTypeDef()) {
        // make sure that if  parameter type is typedef  try to convert to function so we can compare with argument
        parameterType = resolveParameterType(parameterType, argumentClass);
      }


      //TODO mlo: note to self , when argument function, can assign to "Function"

      Optional<ResultHolder> optionalTypeParameterConstraint = findConstraintForTypeParameter(parameterType, typeParamMap);

      // check if  argument matches Type Parameter constraint
      if (optionalTypeParameterConstraint.isPresent()) {
        ResultHolder constraint = optionalTypeParameterConstraint.get();
        if (!canAssignToFrom(constraint, argumentType)) {
          if (parameter.isOptional()) {
            argumentCounter--; //retry argument with next parameter
          }
          else {
            annotateTypeMismatch(holder, constraint, argumentType, argument);
          }
        }
      }
      else {
        ResultHolder resolvedParameterType = HaxeTypeResolver.resolveParameterizedType(parameterType, resolver.withoutUnknowns());
        if (!canAssignToFrom(resolvedParameterType, argumentType)) {
          if (parameter.isOptional()) {
            argumentCounter--; //retry argument with next parameter
          }
          else {
            annotateTypeMismatch(holder, parameterType, argumentType, argument);
          }
        }
      }
    }
  }

  private void checkMethodCall(@NotNull HaxeCallExpression callExpression, HaxeMethod method, AnnotationHolder holder) {

    if (method.getModel().isOverload()) {
      //TODO implement support for overloaded methods (need to get correct model ?)
      return; //(stopping here to avoid marking arguments as type mismatch)
    }


    boolean isStaticExtension = callExpression.resolveIsStaticExtension();
    HaxeExpression methodExpression = callExpression.getExpression();


    HaxeCallExpressionList callExpressionList = callExpression.getExpressionList();
    List<HaxeParameterModel> parameterList = method.getModel().getParameters();
    if (method instanceof  HaxeMethodDeclarationImpl methodDeclaration) {

      if (HaxeMacroUtil.isMacroMethod(methodDeclaration)) {
        parameterList = parameterList.stream().map(this::resolveMacroTypes).toList();
      }
    }
    List<HaxeExpression> argumentList = Optional.ofNullable(callExpressionList)
      .map(HaxeExpressionList::getExpressionList)
      .orElse(List.of());

    boolean hasVarArgs = hasVarArgs(parameterList);
    long minArgRequired = countRequiredArguments(parameterList) - (isStaticExtension ? 1 : 0);
    long maxArgAllowed = hasVarArgs ? Long.MAX_VALUE : parameterList.size() - (isStaticExtension ? 1 : 0);

    // min arg check

    if (argumentList.size() < minArgRequired) {
      String message = HaxeBundle.message("haxe.semantic.method.parameter.missing", minArgRequired, argumentList.size());
      if (argumentList.isEmpty()) {
        PsiElement first = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(methodExpression);
        PsiElement second = UsefulPsiTreeUtil.getNextSiblingSkipWhiteSpacesAndComments(first);
        TextRange range = TextRange.create(first.getTextOffset(), second.getTextOffset() + 1);
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create();
      }
      else {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(callExpressionList).create();
      }
      return;
    }
    //max arg check
    if (argumentList.size() > maxArgAllowed) {
      String message = HaxeBundle.message("haxe.semantic.method.parameter.too.many", maxArgAllowed, argumentList.size());
      holder.newAnnotation(HighlightSeverity.ERROR, message).range(callExpressionList).create();
      return;
    }

    // generics and type parameter
    HaxeGenericSpecialization specialization = callExpression.getSpecialization();
    HaxeGenericResolver resolver = findGenericResolverFromVariable(callExpression.getExpression());

    if (resolver == null && specialization != null) {
      resolver = specialization.toGenericResolver(callExpression);
    }
    if (resolver == null) resolver = new HaxeGenericResolver();

    Map<String, ResultHolder> typeParamMap = createTypeParameterConstraintMap(method, resolver);


    int parameterCounter = 0;
    int argumentCounter = 0;

    if (isStaticExtension) {
      // this might not work for literals, need to handle those in a different way
      if (methodExpression instanceof HaxeReferenceExpression referenceChain) {
        HaxeReference leftReference = HaxeResolveUtil.getLeftReference(referenceChain);
        ResultHolder leftType = HaxeTypeResolver.getPsiElementType(leftReference, resolver);

        HaxeParameterModel model = parameterList.get(parameterCounter++);
        ResultHolder type = model.getType(resolver.withoutUnknowns());
        if (!canAssignToFrom(type, leftType)) {
          // TODO better error message
          holder.newAnnotation(HighlightSeverity.ERROR, "Can not use extension method, wrong type").range(callExpression).create();
          return;
        }
      }
      else {
        // TODO check if literals, like "myString".SomeExtension()
      }
    }

    boolean isRestArg = false;
    HaxeParameterModel parameter = null;
    HaxeExpression argument;

    ResultHolder parameterType = null;
    ResultHolder argumentType = null;

    // checking arguments is a bit complicated, rest parameters allow "infinite" arguments and optional parameters can be "skipped"
    // so we only want to break the loop once we have either exhausted the arguments or parameter list.
    while (true) {
      HaxeGenericResolver localResolver = new HaxeGenericResolver();
      localResolver.addAll(resolver);

      if (argumentList.size() > argumentCounter) {
        argument = argumentList.get(argumentCounter++);
      }
      else {
        // out of arguments
        break;
      }

      if (!isRestArg) {
        if (parameterList.size() > parameterCounter) {
          parameter = parameterList.get(parameterCounter++);
          if (isVarArg(parameter)) isRestArg = true;
        }
        else {
          // out of parameters and last is not var arg, must mean that ve have skipped optionals and still had arguments left
          if (parameterType != null && argumentType != null) {
            annotateTypeMismatch(holder, parameterType, argumentType, argument);
          }
          break;
        }
      }

      localResolver.addAll(method.getModel().getGenericResolver(null).withoutUnknowns());

      argumentType = resolveArgumentType(argument, localResolver);
      parameterType = resolveParameterType(parameter, localResolver);

      // when methods has type-parameters we can inherit the type from arguments (note that they may contain constraints)
      if (containsTypeParameter(parameterType, typeParamMap)) {
        inheritTypeParametersFromArgument(parameterType, argumentType, resolver, typeParamMap);
        // attempt re-resolve after adding inherited type parameters
        parameterType = resolveParameterType(parameter, resolver);
      }

      //TODO properly resolve typedefs
      SpecificHaxeClassReference argumentClass = argumentType.getClassType();
      if (argumentClass != null && argumentClass.isFunction() && parameterType.isTypeDef()) {
        // make sure that if  parameter type is typedef  try to convert to function so we can compare with argument
        parameterType = resolveParameterType(parameterType, argumentClass);
      }


      //TODO mlo: note to self , when argument function, can assign to "Function"

      Optional<ResultHolder> optionalTypeParameterConstraint = findConstraintForTypeParameter(parameterType, typeParamMap);

      // check if  argument matches Type Parameter constraint
      if (optionalTypeParameterConstraint.isPresent()) {
        ResultHolder constraint = optionalTypeParameterConstraint.get();
        if (!canAssignToFrom(constraint, argumentType)) {
          if (parameter.isOptional()) {
            argumentCounter--; //retry argument with next parameter
          }
          else {
            annotateTypeMismatch(holder, constraint, argumentType, argument);
          }
        }
      }
      else {
        ResultHolder resolvedParameterType = HaxeTypeResolver.resolveParameterizedType(parameterType, resolver.withoutUnknowns());
        if (!canAssignToFrom(resolvedParameterType, argumentType)) {
          if (parameter.isOptional()) {
            argumentCounter--; //retry argument with next parameter
          }
          else {
            annotateTypeMismatch(holder, resolvedParameterType, argumentType, argument);
          }
        }
      }
    }
  }

  private HaxeParameterModel resolveMacroTypes(HaxeParameterModel parameterModel) {
    ResultHolder type = parameterModel.getType(null);
    ResultHolder resolved = HaxeMacroUtil.resolveMacroType(type);
    return parameterModel.replaceType(resolved);
  }

  private void inheritTypeParametersFromArgument(ResultHolder parameterType,
                                                 ResultHolder argumentType,
                                                 HaxeGenericResolver resolver,
                                                 Map<String, ResultHolder> typeParamMap) {
    if (argumentType == null) return; // this should not happen, we should have an argument
    HaxeGenericResolver inherit = findTypeParametersToInherit(parameterType.getType(), argumentType.getType(), resolver, typeParamMap);
    resolver.addAll(inherit);
    // parameter is a typeParameter type, we can just add it to resolver
    if (parameterType.getClassType().isFromTypeParameter()) {
      String className = parameterType.getClassType().getClassName();
      resolver.add(className, argumentType);
      typeParamMap.put(className, argumentType);
    }
  }

  private ResultHolder resolveArgumentType(HaxeExpression argument, HaxeGenericResolver resolver) {
    ResultHolder expressionType = null;
    // try to resolve methods/function types
    if (argument instanceof HaxeReferenceExpression referenceExpression) {
      PsiElement resolvedExpression = referenceExpression.resolve();
      if (resolvedExpression instanceof HaxeLocalFunctionDeclaration functionDeclaration) {
        SpecificFunctionReference type = functionDeclaration.getModel().getFunctionType(null);
        expressionType = type.createHolder();
      }
      else if (resolvedExpression instanceof HaxeMethodDeclaration methodDeclaration) {
        SpecificFunctionReference type = methodDeclaration.getModel().getFunctionType(null);
        expressionType = type.createHolder();
      }else if (resolvedExpression instanceof HaxeEnumValueDeclaration valueDeclaration) {
        return  HaxeTypeResolver.getEnumReturnType(valueDeclaration, referenceExpression.resolveHaxeClass().getGenericResolver());
      }
    }
    // anything else is resolved here (literals etc.)
    if (expressionType == null) {
      HaxeExpressionEvaluatorContext context = new HaxeExpressionEvaluatorContext(argument);
      HaxeGenericResolver genericResolver = HaxeGenericResolverUtil.generateResolverFromScopeParents(argument);
      genericResolver.addAll(resolver); // TODO verify if this is ok
      expressionType = HaxeExpressionEvaluator.evaluate(argument, context, genericResolver.withoutUnknowns()).result;
    }

    // if expression is enumValue we need to resolve the underlying enumType type to test assignment
    if (expressionType != null && expressionType.getType() instanceof SpecificEnumValueReference type) {
      SpecificHaxeClassReference enumType = type.getEnumClass();
      expressionType = enumType.createHolder();
    }

    return expressionType;
  }

  private static ResultHolder resolveParameterType(HaxeParameterModel parameter, HaxeGenericResolver localResolver) {
    ResultHolder type = parameter.getType(localResolver.withoutUnknowns());
    // custom handling for macro based rest parameters (commonly used before we got native support for rest parameters)
    if (type.getClassType() != null) {
      SpecificHaxeClassReference classType = type.getClassType();
      @NotNull ResultHolder[] specifics = classType.getSpecifics();
      if (isExternRestClass(classType)) {
        if (specifics.length == 1) {
          return specifics[0];
        }
      }
      if (specifics.length == 1) {
        SpecificTypeReference specificType = specifics[0].getType();
        if (classType.isArray() && specificType instanceof SpecificHaxeClassReference classReference && isMacroExpr(classReference)) {
          type = SpecificTypeReference.getDynamic(parameter.getParameterPsi()).createHolder();
        }
      }
    }
    return type;
  }


  private static ResultHolder resolveParameterType(ResultHolder parameterType, SpecificHaxeClassReference parameterClassType) {
    if (parameterClassType != null) {
      HaxeClass aClass = parameterClassType.getHaxeClass();
      if (aClass != null && aClass.getModel().isTypedef()) {
        SpecificFunctionReference functionReference = parameterClassType.resolveTypeDefFunction();
        if (functionReference != null) {
          parameterType = functionReference.createHolder();
        }
      }
    }
    return parameterType;
  }


  private static HaxeGenericResolver findTypeParametersToInherit(SpecificTypeReference parameter,
                                                                 SpecificTypeReference argument,
                                                                 HaxeGenericResolver resolver, Map<String, ResultHolder> map) {

    HaxeGenericResolver inheritResolver = new HaxeGenericResolver();
    if (parameter instanceof SpecificHaxeClassReference parameterReference &&
        argument instanceof SpecificHaxeClassReference argumentReference) {
      HaxeGenericResolver paramResolver = parameterReference.getGenericResolver().addAll(resolver.withoutUnknowns());
      HaxeGenericResolver argResolver = argumentReference.getGenericResolver().addAll(resolver.withoutUnknowns());
      for (String name : paramResolver.names()) {
        ResultHolder resolve = paramResolver.resolve(name);
        if (resolve != null && resolve.isClassType()) {
          String className = resolve.getClassType().getClassName();

          if (className != null && map.containsKey(className)) {
            ResultHolder argResolved = argResolver.resolve(className);
            if (argResolved != null) {
              resolver.add(className, argResolved);
            }
          }
        }
      }
    }


    return inheritResolver;
  }


  private static void annotateTypeMismatch(AnnotationHolder holder, ResultHolder expected, ResultHolder got, HaxeExpression expression) {
    String message = HaxeBundle.message("haxe.semantic.method.parameter.mismatch",
                                        expected.toPresentationString(),
                                        got.toPresentationString());
    holder.newAnnotation(HighlightSeverity.ERROR, message).range(expression.getTextRange()).create();
  }

  private static int findMinArgsCounts(List<HaxeFunctionArgument> argumentList) {
    int count = 0;
    for (HaxeFunctionArgument argument : argumentList) {
      if (argument.getOptionalMark() == null) {
        if (!isVoidArgument(argument)) count++;
      }
    }
    return count;
  }

  private static boolean isVoidArgument(HaxeFunctionArgument argument) {
    HaxeTypeOrAnonymous toa = argument.getTypeOrAnonymous();
    HaxeType t = null != toa ? toa.getType() : null;
    String name = t != null ? t.getText() : null;
    return SpecificHaxeClassReference.VOID.equals(name);
  }


  private static HaxeGenericResolver findGenericResolverFromVariable(HaxeExpression expr) {
    HaxeReference[] type = UsefulPsiTreeUtil.getChildrenOfType(expr, HaxeReference.class, null);

    if (type != null && type.length > 0) {
      HaxeReference expression = type[0];
      HaxeResolveResult resolveResult = expression.resolveHaxeClass();
      SpecificHaxeClassReference reference = resolveResult.getSpecificClassReference(expression.getElement(), null);
      SpecificHaxeClassReference finalReference = getUnderlyingClassIfAbstractNull(reference);
      return finalReference.getGenericResolver();
    }
    return null;
  }


  private static long countRequiredArguments(List<HaxeParameterModel> parametersList) {
    return parametersList.stream()
      .filter(p -> !p.isOptional() && !p.hasInit() && !isVarArg(p))
      .count();
  }

  private static long countRequiredFunctionTypeArguments(List<HaxeFunctionTypeParameterModel> parametersList) {
    return parametersList.stream()
      .filter(p -> !p.isOptional() && !p.getArgumentType().isVoid() && !p.isRestArgument())
      .count();
  }

  private static boolean hasVarArgs(List<HaxeParameterModel> parametersList) {
    return parametersList.stream().anyMatch(HaxeCallExpressionAnnotator::isVarArg);
  }

  private static boolean isVarArg(HaxeParameterModel model) {
    if (model.isRest()) {
      return true;
    }
    //Legacy solutions for rest arguments
    // Array<haxe.macro.Expr>
    // haxe.extern.Rest<Float>
    // TODO : this is a bit of a hack to avoid having to resolve Array<Expr> and Rest<> Class, should probably resolve and compare these properly
    if (model.getType().getType() instanceof SpecificHaxeClassReference classType) {
      if (classType.getHaxeClass() != null) {
        ResultHolder[] specifics = classType.getSpecifics();
        if (specifics.length == 1) {
          SpecificTypeReference type = specifics[0].getType();
          if (type instanceof SpecificHaxeClassReference specificType) {
            if (specificType.getHaxeClass() != null) {
              // Array<haxe.macro.Expr>
              if (classType.isArray() && isMacroExpr(specificType)) {
                return true;
              }
              // haxe.extern.Rest<>
              return isExternRestClass(classType);
            }
          }
        }
      }
    }
    return false;
  }

  private static boolean isMacroExpr(SpecificHaxeClassReference classReference) {
    if (classReference.getHaxeClass() == null) return false;
    return classReference.getHaxeClass().getQualifiedName().equals("haxe.macro.Expr");
  }

  private static boolean isExternRestClass(SpecificHaxeClassReference classReference) {
    if (classReference.getHaxeClass() == null) return false;
    return classReference.getHaxeClass().getQualifiedName().equals("haxe.extern.Rest");
  }
}
