
## UI 测试（Robolectric）

本机无法启动 Android 模拟器（沙箱禁止嵌套虚拟化：AEHD 驱动可安装且 `emulator-check accel`
报 usable，但 qemu 客户机 CPU 时间始终 <2s、永不进入 boot，API 30/35 镜像都一样）。
因此 UI 正确性用 **Robolectric + Compose UI Test** 在 JVM 上验证：

```bash
bash tools/build.sh :app:testStableDebugUnitTest --tests "io.rune.mt.forum.ForumCardRenderTest"
```

`app/build.gradle.kts` 里的 `testOptions.unitTests.all { }` 把 `user.home` 指到工作区，
否则 Robolectric 会尝试在 `~` 下建下载锁文件而被沙箱拒绝。
`TestApp` 是一个空 `Application`，用来跳过 `App.onCreate` 的 HiddenApiBypass / MonetCompat。

注意：Robolectric 的 `stringResource` 使用默认资源（英文），断言中文会失败——断言要写英文串。
