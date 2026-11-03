# 上传到 GitHub

项目已初始化为本地 Git 仓库，分支为 main；尚未设置远程仓库或推送。

在 GitHub 新建空仓库，然后在项目根目录执行（将地址替换为自己的仓库）：

```powershell
git add .
git diff --cached --stat
git commit -m "Initial project source"
git remote add origin https://github.com/YOUR-USER/YOUR-REPOSITORY.git
git push -u origin main
```

如果 Git 提示缺少身份，先设置自己的 user.name 和 user.email。首次推送需要使用自己的 GitHub 身份登录。

也可解压 outputs/电动车导航_GitHub源码.zip 后，通过 GitHub 网页上传解压后的内容。压缩包本身不应作为源码仓库的唯一内容。

本机依赖、密钥和签名文件均保留在本地并被 .gitignore 排除。网页上传不会应用 .gitignore，请使用已筛选的源码包。APK/BIN、PPT 和历史资料另留本地，未包含在源码包中。

按根目录 README 准备依赖、填写自己的配置，再进行构建和设备验证。真实 Key、Wi-Fi 密码、签名密钥不能上传。自有代码尚未选定开源许可证，第三方代码保留原有授权文件。
