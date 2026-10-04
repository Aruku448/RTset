# CDF 构建提交遗漏导致的 GPU 故障修正

## 本次证据

崩溃报告 `crash-2026-10-04_22.52.33-client.txt`：
`GpuDeviceLossException: VK_ERROR_DEVICE_LOST: Failed to present image`。

22:52:28 输出 CDF 几何创建日志；22:52:33 崩溃。
内核同时记录 Java 渲染线程的 AMDGPU 页保护访问错误、gfx 队列超时及 reset。
这一时段没有 systemd-oomd 终止记录，不能沿用之前的 OOM 解释。

## 确定的代码错误

`SkyCdfAcceleration.create` 通过 `allocateAndBeginTransientCommandBuffer()` 录制独立构建命令，
但结束后没有调用 `encoder.execute(command)`。
该独立命令不属于 encoder 的 current command buffer，`submit()` 不会自动提交它。
于是等待的 fence 没有覆盖 CDF 的 BLAS/TLAS 构建，后续 shader 读取未构建 AS。
这是上一轮新增代码的错误，shader 编译和 CPU 相交检查不足以验证提交路径。

## 修改及回归

先加入结束命令 → execute 入队 → 创建 fence → submit 的顺序检查。
`./gradlew vulkanResourceLifecycleTest --offline` 修改前失败，错误明确指出 TLAS 未构建风险。
随后添加 `encoder.execute(command)`，并检查 `vkEndCommandBuffer` 返回值。

验证通过：

```sh
./gradlew vulkanResourceLifecycleTest rayTracingShaderContractTest rayTracingAtmosphereShaderTest jar --offline
git diff --check
```

这锁定了命令未入队的代码缺陷；契约检查不是 GPU 驱动运行回归。
修复版已重新构建并替换实例 jar，原硬件 CDF 版本已备份。
仍需重启并进入相同场景验证 `VK_ERROR_DEVICE_LOST` 不再发生。
之前的系统内存压力问题是另一条尚未完成定位的问题。
