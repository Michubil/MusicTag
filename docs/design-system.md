# GUI 约定

`:core:designsystem` 是唯一视觉来源，版本见模块 `VERSION`。保留当前配色、字体、形状和动效；来源及许可见 [第三方声明](../core/designsystem/THIRD_PARTY_NOTICES.md)。

- Feature 只传内容、语义图标、状态与 Action，不定义颜色、尺寸、间距、字体或动画参数。
- 共享控件不足时扩展设计系统，不添加视觉 override 或第二套主题。Dynamic Color 只换色板，Activity/Dialog 系统栏跟随实际主题。
- 底栏仅文件/设置，只有选中项显示文字；根标题为应用名。系统返回和 Predictive Back 由 Navigation Compose 处理，不显示返回按钮。
- 切换 Tab 到 Root；子页重选当前 Tab 回 Root，Root 重选无操作。进程恢复时无内存选择的操作页退回目录。
- 离开候选、重命名或标签编辑页只取消读取，不取消文件提交或错误释放 busy。

| 场景 | 约定 |
| --- | --- |
| 列表 | AppContentList / AppRefreshableContentList 复用 LazyListState；空列表可刷新；加载反馈覆盖显示，不插入列表导致行位移，待筛选时允许打开已显示的文件夹，禁用选择与写入 |
| 搜索 | AppTopBar 搜索态用输入框替换标题，关闭为操作图标而不是返回按钮；结果复用文件行，系统返回退出搜索 |
| 底部 | AppScaffold 统一消费底部系统 inset，空底栏和仅操作栏也留白，组合导航栏不重复；IME 弹出时不叠加导航区间距；普通列表随有效滚动隐藏导航，操作页和 TalkBack 下固定 |
| 文件行 | AppThreeLineContentRow 预留文件名/艺术家/专辑三行，单行省略，异步内容不改变行高 |
| 设置 | PreferenceGroup 管理圆角；SwitchRow 整行只有一个 Action，ChoiceRow 用标准单选弹窗；排序确认才提交草稿 |
| 操作 | AppSelectionRow 两侧独立可访问；AppActionBar 两按钮等宽、独立启用 |
| 编辑菜单 | 仅有有效选择且可操作时显示，否则收起菜单；按实测 FAB 坐标定位 |
| 标签编辑 | 单文件顶部封面始终展开，无收起入口；批量不展示图片。表单通栏对齐，修改选择置于输入框内；短多值输入从一行增长，歌词预留三行。保存统一放底栏，新封面仅在勾选修改时展示 |
| 折叠内容 | AppExpandableSection 统一管理规则与说明的展开状态，重要预览优先出现在首屏 |
| 弹窗 | AppModalWindow 共用遮罩、系统栏和动画；退场保持挂载，模糊仅作用底层 Scaffold |

动效参数查 `AppMotion.kt`、`AppMotionScheme.kt` 和测试，遵循系统 duration scale。Preview/tooling 仅用于 debug；测试不能替代真机视觉、无障碍和性能验收。

GUI 调整以日常字体缩放 1.0、约 361 dp 宽竖屏和 IME 不遮挡保存栏为基线；共享问题在设计系统内修复。

通用组件已回馈 AndroidGUI 0.3.0；本项目保留音乐图标和中文业务文案适配。独立框架提供可传入文案、中英文默认文本及通用占位图标，两边构建不引用对方路径。
