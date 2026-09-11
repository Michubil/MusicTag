# 刮削数据源

设置按组合（标题、艺术家、专辑、日期、音轨、碟号）、歌词、图片分别保存查询顺序。默认“网易云 → QQ 音乐”，也可反向或只用其中一个。沿用现有八项字段的启用与覆盖策略。

- 首选源未匹配到可靠歌曲、缺少所选内容或请求失败时，才使用后备源。已取得的有效值不被后备源覆盖。
- 跨源补充使用已匹配歌曲作为参照，核对标题、艺术家、已有专辑和时长；不能可靠对应时保留缺失。手动候选标明来源，编号始终与来源一起传递。
- 手动选择的歌曲优先用于当前搜索类别，其余类别仍按设置查询。同一次刮削复用每个源的匹配与下载结果，不保存匹配历史。
- 只下载勾选类别所需的歌词和图片。错误、限流、结构变化、空响应保持 Unavailable；不能把请求失败当作 ConfirmedAbsent 清除本地字段。
- QQ 使用匿名网页请求，直接访问 QQ 音乐与图片服务器，不包含账号登录、共享 Cookie 或中转服务。限制并发和请求速率，设置超时、响应大小上限，封面仅接受可信域名的 JPEG/PNG。
- QQ 日期按响应保留精度，音轨使用有效的专辑编号；不将含义未确认的 `index_cd` 当作一基碟号，也不猜测音轨/碟片总数。封面只用专辑图片，不以歌手头像替代。

## 接口维护参考

以下社区资料用于核对请求与响应格式；接口变动时应在对应客户端内适配：

- [QQ 搜索与歌曲详情](https://github.com/xwjdsh/qqmusic/blob/main/qqmusic.go)：`music.search.SearchCgiService/DoSearchForQQMusicDesktop`；[歌曲详情接口](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/song.py)：`music.pf_song_detail_svr/get_song_detail_yqq`。
- [专辑详情](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/album.py)：`music.musichallAlbum.AlbumInfoServer/GetAlbumDetail`。
- [网页歌词请求](https://github.com/jsososo/QQMusicApi/blob/master/routes/lyric.js)：`fcg_query_lyric_new.fcg`，原文与翻译 Base64 解码后交给现有 LyricsCodec。
- [歌曲字段与专辑封面地址](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/models/base.py)：使用专辑 MID 构造 `y.gtimg.cn/music/photo_new/T002…` 地址。

这些内部接口没有本项目可控制的兼容性保证；QQ 实际可用率与设备上的行为仍需在用户明确要求验证后确认。
