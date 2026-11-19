# @local/dsh-mobile-bridge

桌面端 DSH Host 插件：给安卓客户端开一个带配对保护的局域网接口，用来查看并操作本机的 DSH 会话。

## 它做什么

- 在 `0.0.0.0:4318` 起一个独立的 `node:http` 服务，不占用也不改动 DSH 自己的 Web GUI 端口
- 6 位配对码换取设备令牌（持久化在 `$DSH_HOME/mobile-bridge.json`）
- 通过 `ctx.sessionController` 暴露会话列表、状态、实时事件、prompt/steer、停止、模型目录与切换
- 通过 `approval/request` 应答者瀑布接管审批（仅在手机最近活跃时）

完整协议见仓库 [`docs/PROTOCOL.md`](../../docs/PROTOCOL.md)。

## 安装

在 DSH profile 里以本地目录安装：

```bash
# 在 profile 目录执行，或使用插件管理器的 install_bundle
pnpm add @local/dsh-mobile-bridge@link:<repo>/desktop-plugin
```

然后把 `@local/dsh-mobile-bridge` 加入 profile 的 `dsh.profile.bundles`（插件管理器安装会自动完成这一步）。重载插件或重启 DSH 后生效。

## 配对

启用后打开 <http://127.0.0.1:4318/pair>，页面上有服务地址与配对码；手机 App 填这两项即可。

## 结构

- `bridge.mjs`：稳定外壳。Loader 对已安装包有模块代际缓存，外壳每次激活都用 cache-busting 重新 `import` 逻辑模块，因此改逻辑后只要禁用/启用插件即可生效
- `logic.mjs`：全部实现（HTTP 路由、鉴权、SSE、事件压缩、审批接管）
- `cordis.patch.yml`：插入 `mobile-bridge` 行，默认端口 4318、监听 `0.0.0.0`
- `locale/`、`icon.svg`、`docs/DETAIL.md`：插件管理器里显示的名称、描述与详情页

## 配置

```yaml
- insert:
    - id: mobile-bridge
      name: '@local/dsh-mobile-bridge'
      config:
        port: 4318        # 监听端口
        host: '0.0.0.0'   # 监听地址；建议只在可信局域网使用
        relayApprovals: true
```

## 安全

- 明文 HTTP，仅限同一局域网；不要把 4318 暴露到公网
- 令牌是唯一访问控制手段，可通过 App 的「解除配对」或删除 `mobile-bridge.json` 吊销
- 手机未连接时插件不介入审批，桌面端行为不变

## 许可

MIT。
