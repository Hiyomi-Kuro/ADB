# ADB

Android Device Bridge (ADB) 是一个基于 Android 平台的悬浮聊天助手项目。

## 项目介绍

本项目提供一个运行在 Android 手机上的浮动窗口交互能力，方便用户通过悬浮界面进行快捷操作和信息交流。应用包名为 com.kaori.adb。

## 技术栈

- Android 原生开发
- Kotlin / Java
- AndroidX 与 Material Components
- ViewBinding

## 项目结构

- app/：Android 应用主要源码与资源
- build.gradle：项目构建配置
- settings.gradle：Gradle 模块配置

## 开发环境

- Android Studio 或 AndroidIDE
- JDK 17
- Android SDK 36

## 构建

使用 Gradle 构建 Debug APK：

```bash
./gradlew assembleDebug
```
