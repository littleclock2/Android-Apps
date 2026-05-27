# Android Apps

Android 蓝牙通信与信号模拟应用合集，用于配合单片机开发调试。

## 软件环境

- IDE：Android Studio
- 语言：Kotlin / Java
- 最低 SDK：见各项目 build.gradle

## 应用列表

| 应用 | 说明 |
|------|------|
| BlueToothConnect | 蓝牙串口通信工具，用于连接单片机蓝牙模块 |
| SignalFake | 信号模拟应用，用于测试信号处理系统 |

## 目录结构

```
Android-Apps/
├── BlueToothConnect/    # 蓝牙通信应用
│   ├── app/             # 应用代码
│   ├── gradle/          # Gradle 配置
│   └── build.gradle.kts
├── SignalFake/          # 信号模拟应用
│   ├── app/             # 应用代码
│   ├── gradle/          # Gradle 配置
│   └── build.gradle.kts
├── LICENSE
└── README.md
```

## 构建说明

1. 用 Android Studio 打开对应项目目录
2. 等待 Gradle 同步完成
3. 连接设备或使用模拟器运行

## 许可证

[CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/deed.zh-hans)

---

Maintained by contributors.


