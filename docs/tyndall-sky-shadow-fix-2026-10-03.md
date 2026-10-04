# 树冠光束缺失：已确认缺口与修复边界

## 02:06反馈：正常画面仍不可见

第二张截图确认上一修复没有恢复可见光束。运行日志确认物理大气bootstrap成功，浓度变更到1.84倍，无对应RT初始化失败。原因尚未确认；不能将source接线及编译通过作为图像验收。

新增F9「空气散射诊断」：debugView=10隔离显示实际有限段L与local scattering；debugView=11显示被RGB阴影扣除的direct scattering。两者使用100倍诊断曝光，正常debugView=0不使用此倍率。诊断输出进入现有emission/raw路径，清空其他AOV和post-NRD aerial L以避免重复叠加；sky miss保留原始当前帧输出。尚需同一视角的诊断图像区分低源强、太阳斜路径透射与遮挡覆盖不足。

诊断版实际physical/legacy shaderc编译和jar任务通过。安装SHA-256：`b8e5f53cfa24411fb8ea6337a891d409a05e76c635b36c33e686f36443836d97`。JAR和配置备份后缀`.bak-airdiag-20261003`；没有自动开启诊断或更改用户浓度。

用户提供的2026-10-03 01:58:45截图显示树叶与天空，但没有清晰空气光束。01:57:51日志确认1.79倍气溶胶完成介质与290-dispatch LUT重建；配置启用physical atmosphere、体积光，quality=3、strength=2，NRD关闭。

源码中确认的缺口：raygen在physical sky miss跳过camera段积分，aerial composite也按depth<=0跳过。因此树冠缝隙后的空气完全不读取几何RGB阴影，改变浓度只改变无遮挡天空，不能产生该区域的遮挡光束。截图本身不能证明这就是全部视觉问题。

修复：主sky miss沿camera.sun.w覆盖的本地段查询太阳/月亮RGB阴影，并累积signed residual `L_direct_shadowed - L_direct_clear`。SkyView包含全程无遮挡L/T，故只替换近段直射源，不再乘sky的有限T或重复加完整L。PNG艺术背景部分按physicalSkyShare缩放残差。残差源强与SkyView一致，不乘旧L-only亮度倍率。局部发光散射也覆盖sky miss，保留无表面像素的现有raw路径。surface随机局部散射继续进入NRD diffuse历史。AOV alpha=1标记sky signed residual，composite保留负值并在合成后截断最终颜色。

介质量级：正确解码extinction.bin.gz.b64后，海平面1.79倍气溶胶在50米上的四波长T为0.99201、0.99358、0.99484、0.99577。当前0–2倍浓度是轻薄空气，短距离散射对比可能弱；没有把最终颜色倍率当作真实浓度。4/8/16步对细小叶缝的空间覆盖仍待验证。局部RGB源的太阳谱加权近似仍存在。

验证：glslc重新生成aerial composite SPIR-V；完整build通过，最终源强边界改动后再次通过实际physical/legacy shaderc编译与jar打包。CPU代数检查验证sky残差替换一次；源码接线检查验证sky不被primaryHit或depth单独排除。这些不是GPU图像复现：没有固定场景重放接口，游戏已退出，原截图的光束效果尚未验证。

部署JAR：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`，SHA-256 `16838f7f38e203ccde586068c7b5e7a7d92a21a4d6ed6189eb013313ba0874df`。旧JAR与最新配置分别备份为同目录`.bak-tyndall-20261003`文件；配置cmp一致。对应源码快照另存实例的rtest-source-snapshots目录。
