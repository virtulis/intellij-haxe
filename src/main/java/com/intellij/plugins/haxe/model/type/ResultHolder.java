/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018 Ilya Malanin
 * Copyright 2019 Eric Bishton
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
package com.intellij.plugins.haxe.model.type;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedList;
import java.util.List;

public class ResultHolder {
  static public ResultHolder[] EMPTY = new ResultHolder[0];

  @NotNull
  private SpecificTypeReference type;
  private boolean initExpression = false;
  private boolean canMutate = true;
  private int mutationCount = 0;

  private PsiElement origin;

  public ResultHolder(@NotNull SpecificTypeReference type) {
    this(type, null);
  }
  public ResultHolder(@NotNull SpecificTypeReference type, @Nullable PsiElement origin) {
    this.type = type;
    this.origin = origin;
  }

  @NotNull
  public SpecificTypeReference getType() {
    return type;
  }

  @Nullable
  public SpecificFunctionReference getFunctionType() {
    return (type instanceof SpecificFunctionReference functionType) ? functionType : null;
  }

  @Nullable
  public SpecificHaxeClassReference getClassType() {
    return (type instanceof SpecificHaxeClassReference classReference) ? classReference : null;
  }
  @Nullable
  public SpecificEnumValueReference getEnumValueType() {
    return (type instanceof SpecificEnumValueReference enumValueReference) ? enumValueReference : null;
  }

  @Nullable
  public boolean isFunctionType() {
    return (type instanceof SpecificFunctionReference);
  }
  @Nullable
  public boolean isClassType() {
    return (type instanceof SpecificHaxeClassReference);
  }
  public boolean isTypeDef() {
    return (type instanceof SpecificHaxeClassReference classReference) && classReference.isTypeDef();
  }
  public boolean isEnum() {
    return (type instanceof SpecificHaxeClassReference classReference) && classReference.isEnumType();
  }

  public boolean isEnumValueType() {
    return (type instanceof SpecificEnumValueReference);
  }

  public boolean isUnknown() {
    return type.isUnknown();
  }
  public boolean missingClassModel() {
    return !isClassType()  || getClassType().getHaxeClassModel() == null;
  }

  public boolean isVoid() {
    return type.isVoid();
  }

  public boolean isDynamic() {
    return type.isDynamic();
  }

  public boolean isTypeParameter() {
    if(type instanceof  SpecificHaxeClassReference classReference) {
      return classReference.getHaxeClassReference().isTypeParameter();
    }
    return false;
  }

  public ResultHolder setType(@Nullable SpecificTypeReference type) {
    if (type == null) {
      type = SpecificTypeReference.getDynamic(this.type.getElementContext());
    }
    this.type = type;
    mutationCount++;
    return this;
  }


  public void disableMutating() {
    this.canMutate = false;
  }

  public boolean hasMutated() {
    return this.mutationCount > 0;
  }

  public boolean canMutate() {
    return this.canMutate;
  }

  public boolean isImmutable() {
    return !this.canMutate;
  }

  static public List<SpecificTypeReference> types(List<ResultHolder> holders) {
    LinkedList<SpecificTypeReference> out = new LinkedList<SpecificTypeReference>();
    for (ResultHolder holder : holders) {
      out.push(holder.type);
    }
    return out;
  }

  public boolean canAssign(ResultHolder that) {
    return HaxeTypeCompatible.canAssignToFrom(this, that);
  }

  public void removeConstant() {
    setType(getType().withoutConstantValue());
  }

  public String toString() {
    return this.getType().toString();
  }

  public String toStringWithoutConstant() {
    return this.getType().toStringWithoutConstant();
  }

  public String toPresentationString() {
    return this.getType().toPresentationString();
  }

  public ResultHolder duplicate() {
    return new ResultHolder(this.getType());
  }

  public ResultHolder withConstantValue(Object constantValue) {
    return duplicate().setType(getType().withConstantValue(constantValue));
  }


  public PsiElement getElementContext() {
    return type.getElementContext();
  }


  public ResultHolder withOrigin(PsiElement origin) {
    return new ResultHolder(this.getType(), origin);
  }
  public PsiElement getOrigin() {
    return origin;
  }
}
