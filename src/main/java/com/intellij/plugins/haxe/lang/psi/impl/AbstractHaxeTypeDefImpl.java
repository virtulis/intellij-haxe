/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018 Ilya Malanin
 * Copyright 2020 Eric Bishton
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
package com.intellij.plugins.haxe.lang.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.plugins.haxe.HaxeComponentType;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author: Fedor.Korotkov
 */
public abstract class AbstractHaxeTypeDefImpl extends AbstractHaxePsiClass implements HaxeTypedefDeclaration {
  public AbstractHaxeTypeDefImpl(@NotNull ASTNode node) {
    super(node);
  }

  public HaxeResolveResult getTargetClass() {
    return getTargetClass(new HaxeGenericSpecialization());
  }

  public SpecificHaxeClassReference getTargetClass(HaxeGenericResolver genericResolver) {

    final HaxeTypeOrAnonymous haxeTypeOrAnonymous = getTypeOrAnonymous();
    if (haxeTypeOrAnonymous == null) {
      // cause parse error
      return null;
    }

    if (haxeTypeOrAnonymous.getAnonymousType() != null) {
      return SpecificHaxeClassReference.withGenerics(
        new HaxeClassReference(haxeTypeOrAnonymous.getAnonymousType().getModel(), this),
        genericResolver == null ? ResultHolder.EMPTY : genericResolver.withoutUnknowns().getSpecificsFor(haxeTypeOrAnonymous.getAnonymousType())
      );
    }

    return SpecificHaxeClassReference.propagateGenericsToType(haxeTypeOrAnonymous.getType(), genericResolver);
  }

  public HaxeResolveResult getTargetClass(HaxeGenericSpecialization specialization) {
    final HaxeTypeOrAnonymous haxeTypeOrAnonymous = getTypeOrAnonymous();
    if (haxeTypeOrAnonymous == null) {
      // cause parse error
      return HaxeResolveResult.createEmpty();
    }
    if (haxeTypeOrAnonymous.getAnonymousType() != null) {
      return HaxeResolveResult.create(haxeTypeOrAnonymous.getAnonymousType(), specialization);
    }
    return HaxeResolveUtil.getHaxeClassResolveResult(haxeTypeOrAnonymous.getType(), specialization);
  }

  @NotNull
  @Override
  public List<HaxeType> getHaxeExtendsList() {
    final HaxeClass targetHaxeClass = getTargetClass().getHaxeClass();
    if (targetHaxeClass != null) {
      return targetHaxeClass.getHaxeExtendsList();
    }
    return super.getHaxeExtendsList();
  }

  @NotNull
  @Override
  public List<HaxeType> getHaxeImplementsList() {
    final HaxeClass targetHaxeClass = getTargetClass().getHaxeClass();
    if (targetHaxeClass != null) {
      return targetHaxeClass.getHaxeImplementsList();
    }
    return super.getHaxeImplementsList();
  }

  @Override
  public boolean isInterface() {
    final HaxeClass targetHaxeClass = getTargetClass().getHaxeClass();
    if (targetHaxeClass != null) {
      return targetHaxeClass.isInterface();
    }
    return super.isInterface();
  }

  @Override
  public boolean isTypeDef() {
    return HaxeComponentType.typeOf(this) == HaxeComponentType.TYPEDEF;
  }

  @Override
  public HaxeGenericResolver getMemberResolver(HaxeGenericResolver resolver) {
    SpecificHaxeClassReference targetClass = getTargetClass(resolver);
    if (targetClass != null) return targetClass.getGenericResolver();
    return new HaxeGenericResolver();
  }

  @NotNull
  @Override
  public List<HaxeMethod> getHaxeMethodsSelf(@Nullable HaxeGenericResolver resolver) {
    final HaxeClass targetHaxeClass = getTargetClass().getHaxeClass();
    if (targetHaxeClass != null) {
      return targetHaxeClass.getHaxeMethodsSelf(resolver);
    }
    return super.getHaxeMethodsSelf(resolver);
  }

  @NotNull
  @Override
  public List<HaxeNamedComponent> getHaxeFieldsSelf(@Nullable HaxeGenericResolver resolver) {
    final HaxeClass targetHaxeClass = getTargetClass().getHaxeClass();
    if (targetHaxeClass != null) {
      return targetHaxeClass.getHaxeFieldsSelf(resolver);
    }
    return super.getHaxeFieldsSelf(resolver);
  }

  @Override
  public HaxeNamedComponent findHaxeFieldByName(@NotNull String name, @Nullable HaxeGenericResolver resolver) {
    final HaxeClass targetHaxeClass = getTargetClass().getHaxeClass();
    if (targetHaxeClass != null) {
      return targetHaxeClass.findHaxeFieldByName(name, resolver);
    }
    return super.findHaxeFieldByName(name, resolver);
  }

  @Override
  public HaxeNamedComponent findHaxeMethodByName(@NotNull String name, @Nullable HaxeGenericResolver resolver) {
    final HaxeClass targetHaxeClass = getTargetClass().getHaxeClass();
    if (targetHaxeClass != null) {
      return targetHaxeClass.findHaxeMethodByName(name, resolver);
    }
    return super.findHaxeMethodByName(name, resolver);
  }
}
