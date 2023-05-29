package com.intellij.plugins.haxe.haxelib;


import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.*;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.plugins.haxe.haxelib.HaxelibCommandUtils.getHaxelibPath;

public class HaxelibNotifier {



  public static void notifyMissingLibrary(@NotNull Module module, @NotNull String libName, @Nullable String version) {


    Notification notification = NotificationGroupManager.getInstance()
      .getNotificationGroup("haxe.haxelib.warning")
      .createNotification(libName, NotificationType.WARNING);

    String message = version == null ?
                     HaxeBundle.message("haxe.haxelib.library.missing.without.version", libName) :
                     HaxeBundle.message("haxe.haxelib.library.missing.with.version", libName, version);

    notification.setTitle(HaxeBundle.message("haxe.haxelib.library.dependencies"))
      .addAction(installLibAction(module, libName, version, notification))
      .setContent(message)
      .notify(module.getProject());
  }

  @NotNull
  private static AnAction installLibAction(@NotNull Module module, @NotNull String libName, @Nullable String version, Notification notification) {
    return new AnAction(HaxeBundle.message("haxe.haxelib.library.missing.install")) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        notification.expire();
        attemptToInstallHaxeLibrary(module, libName, version, notification);
      }
    };
  }

  private static void attemptToInstallHaxeLibrary(@NotNull Module module, @NotNull String libName, @Nullable String libVersion, @Nullable Notification notification) {


    Project project = module.getProject();

    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Haxelib");
    if(toolWindow!= null) {
      toolWindow.show();

      ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
      Content content = toolWindow.getContentManager().getFactory().createContent(consoleView.getComponent(), "Install " + libName, true);
      toolWindow.getContentManager().addContent(content);
      content.setCloseable(true);


      try {
        GeneralCommandLine commandLine = getCommandForLibInstall(module, libName, libVersion);
        KillableColoredProcessHandler handler =
          new KillableColoredProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
        handler.addProcessListener(new ProcessListener() {
          @Override
          public void processTerminated(@NotNull ProcessEvent event) {
            consoleView.print("Process ended, Exit code " + event.getExitCode(), ConsoleViewContentType.LOG_INFO_OUTPUT);
            onComplete(event.getExitCode() == 0 ,module, libName,libVersion);
            if (notification!= null) {

            }
          }
        });

        consoleView.attachToProcess(handler);
        handler.startNotify();
      }
      catch (ExecutionException e) {
        consoleView.print("Failed to install library" + e.getMessage(), ConsoleViewContentType.ERROR_OUTPUT);
      }
    }

  }

  private static GeneralCommandLine getCommandForLibInstall(@NotNull Module module, String libName, String libVersion)  {
    final Sdk sdk = HaxelibSdkUtils.lookupSdk(module.getProject());
    String workDir = ProjectUtil.guessModuleDir(module).getPath();
    String haxelibPath = getHaxelibPath(sdk);

    GeneralCommandLine commandLine = new GeneralCommandLine();

    commandLine.setExePath(haxelibPath);
    commandLine.setWorkDirectory(workDir);

    commandLine.addParameters("install", libName);
    if(libVersion != null) {
      commandLine.addParameter(libVersion);
    }

    commandLine.setRedirectErrorStream(true);


    return commandLine;
  }





  private static void onComplete(boolean success, @NotNull Module module, String libName, String libVersion) {
    Project project = module.getProject();
      if(!success) {

        String message = libVersion == null ?
                         HaxeBundle.message("haxe.haxelib.library.missing.install.failed.without.version", libName) :
                         HaxeBundle.message("haxe.haxelib.library.missing.install.failed.with.version", libName, libVersion);


        NotificationGroupManager.getInstance()
          .getNotificationGroup("haxe.haxelib.warning")
          .createNotification(libName, NotificationType.ERROR)
          .setTitle(HaxeBundle.message("haxe.haxelib.library.dependencies"))
          .setContent(message)
          .notify(project);

      }else {
        HaxelibCache.getInstance().reload();
        HaxelibProjectUpdater instance = HaxelibProjectUpdater.INSTANCE;
        HaxelibProjectUpdater.ProjectTracker tracker = instance.findProjectTracker(project);
        tracker.getSdkManager().getLibraryManager(module).reload();
        if(tracker!= null) instance.synchronizeClasspaths(tracker);
      }
    }

}
