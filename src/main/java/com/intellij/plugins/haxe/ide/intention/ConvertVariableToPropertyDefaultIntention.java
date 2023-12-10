package com.intellij.plugins.haxe.ide.intention;

import com.intellij.plugins.haxe.HaxeBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ConvertVariableToPropertyDefaultIntention extends ConvertVariableToPropertyIntentionBase {

  @Nls
  @NotNull
  @Override
  public String getFamilyName() {
    return HaxeBundle.message("haxe.quickfix.var.to.property");
  }

  @NotNull
  @Override
  public String getText() {
    return HaxeBundle.message("haxe.quickfix.var.to.property.default");
  }


  @NotNull
  protected String getPropertyElementString() {
    return "var tmp (default, default) :Int;";
  }
}