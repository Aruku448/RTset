# 告示牌文字与地图接入 RT

## 原生入口

- 在 SubmitNodeCollection.submitText 捕获世界文本，复用 Font.prepareText／prepare8xTextOutline 的排版、字形、字体图集和原生 pose。支持普通／悬挂告示牌正反面、颜色、发光墨囊及轮廓；GUI 文本没有世界 owner，不进入捕获。
- 发光属性取自 AbstractSignRenderer.submitSignText 的 SignText.hasGlowingText，避免把普通白天亮度误识别为发光文字。
- MapRenderer 的地图面和装饰使用 text 管线的自定义四边形。实体 owner 允许捕获这类透明管线，覆盖展示框地图；第一人称在原生 custom submit 时直接捕获到手持物网格。地图装饰名称同样走文本捕获。
- 继续绑定原生地图动态纹理与字体 atlas，未缓存像素副本；地图更新和新增字形使用原生上传流程。

## 材质与几何

地图／字体顶点不写 normal。捕获器仅对缺失法线的完整 quad 以最终变换后的几何叉积补单位法线，原生已有法线保持原样。

RGBA 字体／地图沿普通透明裁切路径；R8 灰度字体使用材质 alpha 模式 3，将纹理 R 转为覆盖率、RGB 设为白色，再乘文本颜色。closest-hit、primary any-hit 和 shadow any-hit 保持一致。模式 2 仍只用于信标 UV 重复，不作用于字体。

原生 polygon offset 无法直接用于 BLAS，文本前景和轮廓追加小幅世界空间法线偏移，保持层次。较多文字分片保存于独立的 0x8002 身份命名空间，每片受现有 512 三角限制。所有字体页在三角材质中保留各自的纹理槽。

## 验证与限制

测试覆盖原生地图形式的无 normal 四边形、单位法线、灰度／RGBA 材质区分、第一人称 custom submit 到手持物 snapshot、文字分片身份及三类射线透明度实现一致性；共享 RT shaders 经 shaderc 编译。已有物品／方块实体模型回归检查通过。

实际游戏中的字体图集绑定、混合语言与发光文字、地图更新、展示框旋转及镜中呈现仍需客户端验证。现有动态实例总容量和动态纹理槽仍有限；文字过多会消耗更多实例。
