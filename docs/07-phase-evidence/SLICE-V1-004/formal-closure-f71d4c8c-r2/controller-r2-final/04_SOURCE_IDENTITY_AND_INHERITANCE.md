# 源码身份、差异及证据继承

## 1. exact对象

最终Head `f71d4c8c2bdf5dc6497d7951d1cd5b12b0122f10`，Tree `b8a9e7d0450d24f2b58b95d6b370daf9cd5e5e47`；实现Head `d65c9185adc89955d6bab3b20ac7bd9f639b5335`，Tree `e01e510d5b8bce57f7556d3e5d6996a01f8d2d74`。原R2已核对唯一父子关系与docs-only最终提交。本轮再次按准确SHA读取GitHub commit，身份保持不变；不追随分支head，不宣称观察了实施者工作树或origin领先数。

当前生产helper的Git blob仍为`60decd5b3543651d38ba35d1ec5a944e88a32c1a`；其原R2已独立取得的完整字节SHA-256 `36ea764ffeeeca717923bed0df6bae6622e8e708202ec20e1a8dec40c73b425c`与本次new manifest一致。

## 2. 本次前后清单

39d55e30→d65：后端1323→1323、恰3项变化；即原helper及两份已审阅测试。前端126→126、0变化。migration目录124份文件与6cc时清单逐文件一致；V0124仍为`f3fe79e8237dc088a54a4e00bff29afb0e6278f7b4c9055f5bad6d4114bb3802`。

d65→f71只有13个docs文件（11修改2新增），不改变已验证代码。原25项关闭不重新打开；消费受影响的SUMMARY/DETAIL/Outcome/lineage回归有本次实际运行记录，非影响路径保持其历史证据，不伪称所有历史suite再次运行。

## 3. 跨阶段唯一表示差异 — 不隐藏，也不制造返工

6cc的new backend manifest与下一阶段39d55e30的old manifest，1323项中只有`backend/marketops-server/mvnw.cmd`不同：

- 旧索引：`8c720db91d96ba5f67afa4083726b20eb4be399d86bb859e09eb67436bb230f5`。
- 新索引：`7ba5a79e1082c47e648fe3ca7bb23b967b6307093d778a0202540683e77ac3ea`。

在既有准确源码包取出完整`mvnw.cmd`，Git blob为`8366e217043bbc6236f863fea070c1da3ffa1f61`，与GitHub 6cc、f71两端相同。原LF字节给出新哈希；仅将LF转换CRLF，正好给出旧哈希。仓库已有`.gitattributes`声明`*.cmd text eol=crlf`。本轮不修改两份输入清单或源文件，不笼统声称跨阶段1323项全部字节相同。

该差异仅为非执行的Windows wrapper表示；原测试命令均为`./mvnw`，其清单值不变，124份SQL和其余1322个后端条目一致。因此接受为作用域明确的继承解释，不属于未关闭生产实现差异。

## 4. 证据类型不能混合

历史完整backend 1895+1363、frontend 418及browser 24/26+2/2沿此前Controller接受记录保持。新37项定向case与76项架构记录不等于当前完整suite覆盖率或新一轮浏览器验收。测试发生在commit形成之前的同一工作树，按实施checkpoint声明和随后提交源码manifest绑定；未宣称对不可见工作站做了独立实地鉴证。
