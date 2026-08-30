# MineGPT

[English](README.md)

在 Minecraft 1.21.1 中使用 ChatGPT 桌面版作为游戏助手。在 Minecraft 聊天栏中输入
带有 `@gpt` 前缀的问题，即可收到本地聊天消息形式的回复。MineGPT 仅运行在客户端：
不需要在服务器安装 Mod 或插件，`@gpt` 消息也不会发送到多人服务器或其他玩家。

```text
Minecraft @gpt 消息 -> 本地 MineGPT Bridge -> ChatGPT 桌面版 -> 本地回复
```

## 安装 MineGPT

1. 将 MineGPT Mod JAR 放入要游玩的 Minecraft 实例的 `mods` 文件夹。Fabric 用户还
   必须在同一实例中安装 Fabric API。
2. 将 MineGPT Bridge 发行包解压到计算机上一个会持续保留的位置。稍后需要在 ChatGPT
   桌面版中填写其启动脚本的绝对路径：

   - macOS/Linux：`bin/minegpt-bridge`
   - Windows：`bin/minegpt-bridge.bat`

3. 启动一次所选的 Minecraft 实例。MineGPT 只在本地客户端运行，无需在多人服务器上
   安装任何内容。

## 连接 ChatGPT 桌面版

1. 在 ChatGPT 桌面版中打开 **设置 -> MCP servers**，添加一个名为 `minegpt` 的
   **STDIO** 服务器。
2. 将命令设置为上一节中 Bridge 启动脚本的绝对路径。无需填写参数或 API Key。
3. 重启 ChatGPT 桌面版，然后在对话中运行 `/mcp`，确认 `minegpt` 已连接。
4. 在该对话中要求 ChatGPT 调用 `minegpt_pairing_code`，然后复制返回的 `token`。
5. 在 Minecraft 中运行：

   ```text
   /minegpt pair <token>
   ```

6. 告诉 ChatGPT 开始监听游戏内问题。可使用以下提示词：

   ```text
   Start listening to Minecraft. For every MineGPT player message, answer it,
   call minegpt_reply with the exact message_id, then immediately call
   minegpt_next_message again with wait_seconds 45. Continue until I tell you
   to stop.
   ```

监听期间请保持这个 ChatGPT 对话处于打开状态。Minecraft 无法自行打开或唤醒对话。

离开 Minecraft 世界或返回标题界面不会结束 MineGPT Bridge 会话；只有退出整个
Minecraft 客户端时，Bridge 才会关闭。

## 游戏内使用

- 在普通 Minecraft 聊天栏中输入 `@gpt <消息>`。例如：

  ```text
  @gpt 我背包里的材料可以合成什么？
  ```

- ChatGPT 的回复会显示为本地 `[MineGPT]` 系统消息。没有 `@gpt` 前缀的普通聊天不会
  受到影响。
- 使用 `/minegpt status` 查看配对状态、Bridge 连接情况和待发送消息数量。
- 使用 `/minegpt github <github_url>` 将一个公开 GitHub Markdown Skill 安装到当前
  Minecraft 实例。支持普通 GitHub 文件页 URL 和 `raw.githubusercontent.com` URL，
  不会覆盖已有 Skill。

## 添加 Skill

MineGPT 会为每个 Minecraft 实例创建可编辑的 Skill 文件夹：

```text
<游戏运行目录>/minegpt/skills/
```

大多数启动器的游戏运行目录是该实例的 `.minecraft` 文件夹。因此 Prism、Modrinth 等
不同实例会使用各自独立的 Skill。可以直接放入 Markdown 文件，也可使用子目录，例如
`building/redstone/guide.md`。路径最多支持八层目录，每个文件最大为 256 KiB。

内置的 `minegpt-guide.md`、`live-data/SKILL.md` 与
`modpack-recipe-investigation/SKILL.md` 被删除后会自动恢复。
`live-data/SKILL.md` 规定 ChatGPT 如何选择只读 MCP 工具来读取玩家状态、背包、实体、
方块、区块和环境数据；整合包配方调查 Skill 会要求它先检索当前实例的本地数据包、KubeJS、
配置、FTB Quests 文件和相关 Mod JAR 资源，再将合成或推进路线表述为已确认。你添加的
Skill 文件不会被覆盖或重新生成。

## 隐私、安全与限制

MineGPT 只监听 `127.0.0.1:37832`，并通过随机 Token 将游戏与 ChatGPT 桌面版配对。
Bridge 会在 `~/.minegpt/bridge-state.json` 中保留 Token 和最多 200 条待处理消息，
保存时间为 24 小时；Minecraft 客户端只会在 `config/minegpt.json` 中保存配对 Token。

已连接的 ChatGPT 对话可以读取来自当前 Minecraft 客户端的有限、只读信息。它不能执行
Minecraft 命令、移动玩家、修改世界、与服务器交互、加载新区块，也无法访问完整聊天
记录、物品或方块实体 NBT，或客户端尚未加载的区块。游戏文件访问仅限于已配对实例的
目录，任何超出该目录的路径都会被拒绝。

如果 ChatGPT 正处于两次工具调用之间，Bridge 会将消息排队。如果 Bridge 不可用，Mod
会报告该问题，并在内存中保留最多 200 条未发送消息，直至重新连接或 Minecraft 关闭。

完整的 MCP 工具参考请参阅 [CHANGELOG.md](CHANGELOG.md)。

## 从源码构建

仅当没有可用的 Mod JAR 和 Bridge 发行包时，才需要执行这些步骤。构建 Bridge，以及你
要使用的一个客户端 Mod：

```sh
cd bridge
./gradlew installDist
```

Fabric：

```sh
cd fabric
./gradlew build
```

或 NeoForge：

```sh
cd neoforge
./gradlew build
```

Bridge 会写入 `bridge/build/install/minegpt-bridge`；Mod JAR 会写入所选项目的
`build/libs` 目录。
