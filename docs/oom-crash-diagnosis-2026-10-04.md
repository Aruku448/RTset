# 游戏退出诊断：systemd-oomd 杀进程

## 已确认的直接原因

系统日志记录了三次内存压力终止：

| 时间（北京时间） | 事件 |
| --- | --- |
| 20:33:41 | systemd-oomd 选中 Java 桌面应用 cgroup，swap 使用约 15 GiB |
| 20:40:57 | 同类 cgroup 再次被杀，swap 使用约 15.2 GiB；进程组退出状态 `9/KILL` |
| 20:45:20 | 调查期间新启动的 Minecraft 所属 cgroup 再次被杀 |

20:40:57 的判定：系统内存使用 32024768512 / 33566982144 字节，
交换空间使用 30223966208 / 33565962240 字节，均超过 90% 阈值。
旧 Minecraft 日志在同一秒结束，结束前仍为成功的 RT 渲染和增量场景更新。

没有找到当日新的 Java crash report、`hs_err_pid` 或 systemd core dump。
新启动的客户端 PID 2952420 所属 cgroup，与 20:45:20 被杀的 cgroup 精确匹配。
所以“系统内存压力触发强制终止”已由实际退出事件确认。

## 调查期间的内存快照

对 PID 2952420 读取 `/proc/PID/status`：

- 一个快照：常驻约 18.55 GiB，换出约 1.45 GiB。
- 随后快照：常驻约 14.52 GiB，换出约 13.96 GiB，合计约 28.48 GiB。
- 启动参数：`-Xmx13053m`。
- 同时执行 `jcmd 2952420 GC.heap_info`：堆 committed 2686976 KiB，used 2340956 KiB，
  即 committed 约 2.56 GiB、used 约 2.23 GiB。

这些指标提示大量占用来自 Java 堆以外的映射、原生或图形资源。
进程 RSS 含共享页，这些快照不能直接当成精确的原生 malloc 泄漏大小。
没有获得 smaps 或 Native Memory Tracking 细分：执行下一次查询时进程已被终止。
尚未定位具体分配点，因此不能断言某个 Vulkan 缓冲或某个 mod 是根因。

天空重要性表为固定 576 KiB，随天空盒一次创建；当前源码在天空盒销毁时释放。
该固定容量本身无法解释上述数十 GiB 占用，但这不是 GPU 资源生命周期的完整证明。

## 复查命令

```sh
journalctl -u systemd-oomd --since '2026-10-04 20:30:00' --no-pager -o short-iso
coredumpctl list --since '2026-10-04 20:25:00' --no-pager
```

日志中的 narrator/flite、旧资源包模型警告不带有对应退出堆栈；本次直接退出证据来自 oomd。
没有更改系统 OOM 策略、启动堆大小、运行配置或 mod。

## 后续定位范围

在下一次运行开始时采集进程 RSS/PSS/swap、Java committed/used、匿名与 DRM 映射，
并与 RT 启停、动态 BLAS 更新、场景发布及暂停阶段对应。
如果原生增长可稳定复现，再对 VMA 活跃分配、驱动映射或 native allocator 做针对性统计。
