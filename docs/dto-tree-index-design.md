# DTO Tree Index 设计文档

## 1. 背景

当前项目需要解决一类 DTO 映射问题：

- DTO 之间存在嵌套关系；
- 叶子属性大多是定长字符串；
- 目标 DTO 的某个属性，可能需要从源 DTO 的多个属性中截断、拼接得到；
- 输入元数据是图结构，而业务处理更适合树结构和区间查询。

为此，`DtoTreeIndexBuilder` 做了两件事：

1. 把图结构元数据转换为一棵 DTO 树；
2. 把树中的叶子属性拍平成有序区间，并建立全局跳表索引，支持快速区间命中。


## 2. 输入数据模型

### 2.1 类节点

```json
{
  "uuid": "...",
  "nodeType": "DtoClass",
  "className": "..."
}
```

### 2.2 属性节点

```json
{
  "uuid": "...",
  "nodeType": "DtoField",
  "propertyId": "...",
  "dataType": "String | Dto | ...",
  "partsDto": {
    "partsDtoUuid": "...",
    "partsDtoId": "..."
  },
  "positionAtClass": {
    "startIndex": 0,
    "endIndex": 8
  }
}
```

### 2.3 边

```json
{
  "fromUuid": "...",
  "toUuid": "...",
  "edgeType": "BELONG_TO"
}
```

含义是：属性节点属于某个类节点。


## 3. 总体设计

整体流程分为 4 步：

1. 解析图结构；
2. 构建 DTO 树；
3. 拍平叶子属性并建立全局跳表；
4. 根据目标窗口和源窗口做区间交集、截断、拼接规划。

流程示意：

```text
javaNodeList/javaEdgeList
        ->
   DTO 树 Node
        ->
  叶子属性有序列表
        ->
 全局 RangeJumpTable
        ->
 区间命中 RangeMatch
        ->
 AssignmentSourceProperty
```


## 4. 核心数据结构

### 4.1 `Node`

`Node` 是树结构中的统一节点，既可以表示 DTO 类，也可以表示普通属性、DTO 属性。

关键字段：

- `uuid`：对应原始图节点的 uuid
- `fieldType`：字段类型；DTO 节点固定为 `Dto`
- `fieldName`：字段名或类名
- `startIndex`：相对根节点的绝对起始偏移
- `len`：节点长度
- `positionAtClass`：相对所属类的原始区间
- `dtoClassUuid`：当节点表示 DTO 时，对应 DTO 类的 uuid
- `children`：子节点

设计重点：

- `startIndex` 用于全局统一索引；
- `positionAtClass` 保留原始业务语义；
- 两套坐标并存，避免后续查询时重复反推。


### 4.2 `RangeMatch`

`RangeMatch` 表示一次区间命中结果。

关键字段：

- `sourceNode`：命中的源叶子属性
- `overlapStart` / `overlapLen`：命中区间在根坐标系下的绝对区间
- `sourceClassOverlapStart` / `sourceClassOverlapLen`：命中区间在源属性所属类坐标系下的区间
- `sourceOffset`：命中区间相对源属性自身的偏移
- `targetOffset`：命中区间相对目标窗口的偏移

这个对象主要服务于：

- 截断计算；
- 拼接顺序；
- 后续代码生成。


### 4.3 `AssignmentSourceProperty`

这是当前对外暴露的最终结果对象。

字段说明：

- `propertyUuid`：源属性 uuid
- `propertyName`：源属性名
- `pathKeyValues`：从根节点到当前属性的完整路径
- `targetInsufficient`：目标窗口是否不足
- `targetMissingLen`：目标窗口还差多少长度
- `sourceInsufficient`：源窗口是否不足
- `sourceMissingLen`：源窗口还差多少长度
- `needTruncate`：该源属性是否需要截断
- `truncateOffset`：截断起始偏移
- `truncateLen`：截断长度


## 5. 两套坐标系

这是整个设计里最重要的概念。

### 5.1 相对所属类坐标

原始 `JavaNode.positionAtClass` 使用的是“相对所属类”的坐标系。

例如：

- `body` 在根类中的区间是 `[4, 12)`
- `code` 在 `body` 类中的区间是 `[0, 3)`

这套坐标是业务输入的原始语义。


### 5.2 相对根节点的绝对坐标

构树时，每个节点都会计算 `startIndex`，表示它相对根节点的绝对起点。

例如：

- `body.startIndex = 4`
- `code.startIndex = 4`
- `desc.startIndex = 7`

这套坐标用于：

- 全局排序；
- 跳表索引；
- 绝对区间交集。


### 5.3 为什么需要两套坐标

如果只有相对所属类坐标：

- 无法直接在整棵树范围内做统一索引。

如果只有绝对坐标：

- 就丢失了原始业务定义，调用方很难理解“参数 offset/len 应该怎么解释”。

所以最终设计是：

- 输入参数按“相对所属类”解释；
- 索引查询按“相对根节点绝对区间”执行。


### 5.4 坐标换算公式

设某个树节点为 `node`：

- `node.startIndex`：相对根节点的绝对起点
- `node.positionAtClass.startIndex`：相对所属类的起点

则可以反推出该节点所属类在根坐标系下的绝对起点：

```text
ownerAbsoluteStart = node.startIndex - node.positionAtClass.startIndex
```

如果某个窗口参数是“相对所属类”的：

```text
localRange = [localStart, localStart + localLen)
```

则换算成绝对区间为：

```text
absoluteStart = ownerAbsoluteStart + localStart
absoluteLen = localLen
```

这也是为什么当前实现可以同时兼容：

- 输入层继续使用业务熟悉的“所属类坐标”
- 索引层统一使用“根绝对坐标”


## 6. 图转树设计

### 6.1 根节点识别

先收集所有被 `partsDto.partsDtoUuid` 引用到的类节点，
再从全部类节点中找出“未被其他 DTO 属性引用”的那个类，作为根类。

当前约束：

- 必须且只能存在一个根类；
- 如果找到 0 个或多个，会直接抛异常。


### 6.2 建树策略

对于每个类节点：

- 先根据边找到它拥有的所有属性；
- 按 `positionAtClass.startIndex` 排序；
- 普通属性直接作为叶子节点加入树；
- DTO 属性则继续递归展开引用的类节点。

### 6.3 长度计算

普通字段长度来自：

- `endIndex - startIndex`
- 或 `endIndex - startIndex + 1`

具体取决于 `RangeMode`：

- `END_EXCLUSIVE`
- `END_INCLUSIVE`

DTO 属性长度取：

- 原始声明长度；
- 子树展开后的实际长度；

两者中的较大值。


### 6.4 为什么根节点的 `positionAtClass` 是 `(0, len)`

根节点在原始图结构里通常没有“所属类”的概念，
但为了让整套窗口逻辑统一处理：

- 根节点也保留一个合成的 `positionAtClass`
- 其值固定为 `(0, root.len)`

这样做的好处是：

- 根类也能走和普通节点一致的窗口裁剪逻辑
- 不需要为根节点写额外分支
- 对调用方来说，根对象也可以像普通对象一样参与查询


## 7. 为什么使用全局跳表

### 7.1 问题

目标属性和源属性最终都要落到叶子属性上处理。

如果每次查询都：

- 从源子树递归收集叶子；
- 排序；
- 再逐个线性比较；

那么在频繁映射场景下成本会比较高。


### 7.2 方案

当前采用一份全局跳表：

- 先把整棵树的叶子属性按绝对起点排序；
- 用这些叶子建立 `RangeJumpTable`；
- 每次先按绝对区间查候选；
- 再按源子树过滤。

这样做的好处：

- 索引只建一次；
- 不需要为每个子树单独维护一份索引；
- 兼顾性能和复杂度。


### 7.3 为什么不是每层都建索引

如果每个类节点、每个 DTO 属性节点都各建一份跳表：

- 内存会重复占用；
- 同一批叶子会被重复组织；
- 构建和维护成本较高；
- 多数子树可能根本不常用。

所以当前策略是：

- 默认只维护一份全局叶子跳表；
- 通过父子链过滤，把候选结果缩小到源子树内部。


## 8. 查询与赋值规划设计

### 8.1 无窗口参数

如果调用方只传目标节点或源节点，不传 `offset/len`：

- 目标区间 = 目标节点自身区间
- 源区间 = 源节点自身区间

这里的“自身区间”仍然是相对所属类定义的区间。


### 8.2 有窗口参数

如果调用方额外传了 `offset/len`：

- 参数语义统一解释为“相对所属类”的窗口；
- 节点自身区间和参数窗口先在所属类坐标系下取交集；
- 再换算成绝对区间。

也就是说，最终有效区间不是简单使用参数窗口，而是：

```text
effectiveRange = intersect(nodeRange, windowRange)
```

这里有一个很重要的约定：

- `offset/len` 不是相对“当前节点自身”
- 而是相对“当前节点所属类”

例如：

- `field-body` 在根类中的区间是 `[4,12)`
- 调用 `findSourceFields("field-body", 5, 10)`

这里的 `[5,15)` 不是相对 `body` 自己，
而是相对 `RootDto` 的区间。

最终有效区间是：

```text
intersect([4,12), [5,15)) = [5,12)
```

所以查询结果会同时命中：

- `code` 的后 2 位
- `desc` 的 5 位


### 8.3 目标和源的交集

目标窗口和源窗口分别换算成绝对区间后，
还需要再做一次交集：

```text
finalRange = intersect(targetAbsoluteRange, sourceAbsoluteRange)
```

只有这个最终交集，才是真正参与赋值的区间。


### 8.4 全局跳表查询

对 `finalRange` 执行：

```text
rangeJumpTable.findOverlaps(finalRange.start, finalRange.len)
```

跳表返回的是全局候选命中结果，
接着再通过父子链判断这些候选是否属于源子树。

这样设计的原因是：

- 跳表只负责“快速找到与绝对区间相交的叶子”
- 不负责理解“这个叶子是不是当前源对象子树里的”
- 子树过滤由树索引结构负责

职责分离后，整体实现更稳定，也更容易扩展


### 8.5 结果对齐

跳表返回的是绝对区间命中结果，
还要进一步转换成：

- 相对目标窗口的偏移
- 相对源属性自身的偏移
- 相对源属性所属类的偏移

这些偏移最终写入 `RangeMatch` 和 `AssignmentSourceProperty`。


### 8.6 无窗口与有窗口的差别

#### 无窗口参数

```java
tree.listSourceAssignmentProperties(targetNode, sourceNode);
```

语义是：

- 目标节点使用自身完整区间
- 源节点使用自身完整区间

#### 有窗口参数

```java
tree.listSourceAssignmentProperties(targetNode, targetOffset, targetLen, sourceNode, sourceOffset, sourceLen);
```

语义是：

- 目标节点先用自身区间和目标窗口求交集
- 源节点先用自身区间和源窗口求交集
- 再对目标有效区间和源有效区间求交集
- 最后才进入跳表查询

所以“有窗口参数”并不是简单截一刀，而是多次区间裁剪后的综合结果


## 9. 路径信息设计

`AssignmentSourceProperty.pathKeyValues` 用于表达：

- 从根节点开始
- 经过哪些类节点和属性节点
- 最终到达当前源叶子属性

路径中的每个元素都是一个 `Map<String, Object>`，当前包含：

- `uuid`
- `type`
- `name`
- `javaNode`

典型路径可能是：

```text
RootDto 类
-> source DTO 属性
-> SourceDto 类
-> part1 叶子属性
```

这个结构便于后续做：

- 日志输出
- 路径显示
- 代码生成
- 前端展示


## 10. 对外主要使用方式

### 10.1 先构建索引树

```java
DtoTreeIndexBuilder builder = new DtoTreeIndexBuilder();
DtoTreeIndexBuilder.DtoIndexedTree tree = builder.build(javaNodeList, javaEdgeList);
```

如果你的 `endIndex` 是闭区间：

```java
DtoTreeIndexBuilder builder =
    new DtoTreeIndexBuilder(DtoTreeIndexBuilder.RangeMode.END_INCLUSIVE);
```


### 10.2 查询某个目标节点对应的区间命中

按节点自身区间查询：

```java
List<DtoTreeIndexBuilder.RangeMatch> matches =
    tree.findSourceFields("field-body");
```

按所属类坐标窗口查询：

```java
List<DtoTreeIndexBuilder.RangeMatch> matches =
    tree.findSourceFields("field-body", 5, 10);
```


### 10.3 获取最终的源属性赋值结果

按目标属性和源对象：

```java
List<DtoTreeIndexBuilder.AssignmentSourceProperty> properties =
    tree.listSourceAssignmentProperties("field-target", sourceClassNode);
```

按目标/源 uuid：

```java
List<DtoTreeIndexBuilder.AssignmentSourceProperty> properties =
    tree.listSourceAssignmentProperties("field-target", "field-source");
```

按窗口查询：

```java
List<DtoTreeIndexBuilder.AssignmentSourceProperty> properties =
    tree.listSourceAssignmentProperties(
        "field-target", 1, 10,
        "field-source", 2, 10);
```


### 10.4 常见调用场景

#### 场景一：只关心目标属性最终由哪些源属性组成

```java
List<DtoTreeIndexBuilder.AssignmentSourceProperty> properties =
    tree.listSourceAssignmentProperties("field-target", "field-source");
```

适用于：

- 做属性映射分析
- 判断是否需要截断或拼接
- 生成映射关系表


#### 场景二：只想看某个对象或属性区间内有哪些叶子属性

```java
List<DtoTreeIndexBuilder.RangeMatch> matches =
    tree.findSourceFields("field-body");
```

适用于：

- 调试树和区间索引
- 验证某个 DTO 属性展开后的叶子布局
- 排查窗口区间命中逻辑


#### 场景三：业务上只处理某个局部窗口

```java
List<DtoTreeIndexBuilder.AssignmentSourceProperty> properties =
    tree.listSourceAssignmentProperties("field-target", 1, 10, "field-source", 2, 10);
```

适用于：

- 只映射目标对象中的一段
- 只允许从源对象中的一段取值
- 需要精确判断“窗口不足”的场景


## 11. 返回结果如何理解

以某个 `AssignmentSourceProperty` 为例：

- `propertyUuid/propertyName`
  表示当前参与赋值的源属性；
- `needTruncate = true`
  表示只取了该源属性的一部分；
- `truncateOffset`
  表示从源属性第几个字符开始取；
- `truncateLen`
  表示需要取多少长度；
- `targetInsufficient/sourceInsufficient`
  表示目标窗口或源窗口超出了各自可用范围；
- `targetMissingLen/sourceMissingLen`
  表示不足的长度。


### 11.1 `needTruncate` 和 `sourceInsufficient` 的区别

这两个字段容易混淆：

- `needTruncate = true`
  表示当前这一个源属性只取了部分内容
- `sourceInsufficient = true`
  表示调用方给定的“源窗口”本身超出了源节点可用范围

也就是说：

- `needTruncate` 是叶子属性维度的信息
- `sourceInsufficient` 是整体窗口维度的信息

两者可能同时为真，也可能只出现一个。


### 11.2 为什么多个结果对象里会重复出现不足信息

`AssignmentSourceProperty` 当前是最终对外结果对象，
为了让调用方只依赖这一种结构，
整体窗口状态也被一起下沉到了每个结果对象里。

这意味着：

- 同一次映射返回多个源属性时
- `targetInsufficient/sourceInsufficient` 等整体字段
- 会在每个结果对象中重复出现

这是一个有意为之的设计取舍，目的是降低调用复杂度。


## 12. 当前设计约束

当前实现有几个明确约束：

- 只允许存在一个根 DTO 类；
- 同一个 DTO 类如果在树中被物化多次，类级入口会认为有歧义并抛异常；
- 跳表索引的是叶子属性，不直接索引类节点；
- 参数窗口统一按“相对所属类”解释。


### 12.1 当前不适合的场景

以下场景当前实现还不适合直接使用：

- 一个图里存在多个根 DTO
- 同一个 DTO 类会在多条路径下重复实例化，但又只传类 uuid 不传具体路径
- 叶子属性不是定长区间，长度依赖运行时内容
- 需要在一个索引结构里同时支持增删改节点

如果后面进入这些场景，建议重新评估索引策略和参数定位方式


## 13. 后续可扩展方向

后续如果业务继续扩展，建议优先考虑这些方向：

### 13.1 结果对象进一步结构化

当前 `pathKeyValues` 使用 `List<Map<String, Object>>`，灵活但弱类型。

如果需要更强约束，可以改成显式 DTO，例如：

```java
record PathNodeInfo(String uuid, String type, String name, JavaNode javaNode)
```


### 13.2 支持重复物化的 DTO 类

如果同一个 DTO 类可能在树里出现多次，
则可以通过“路径 + uuid”的方式定位具体实例，
而不是只靠类 uuid。


### 13.3 代码生成

当前已经能给出：

- 哪些源属性参与赋值
- 是否需要截断
- 从哪里截
- 如何拼接

下一步可以直接在此基础上生成 Java 赋值代码或表达式。


## 14. 总结

`DtoTreeIndexBuilder` 的核心设计思想可以概括为三句话：

1. 输入保留业务原始坐标，内部统一转成树结构；
2. 查询阶段统一转成绝对区间，再用全局跳表做快速命中；
3. 最终结果再回到业务可理解的结构，输出截断、拼接和路径信息。

这套设计兼顾了：

- 图结构到树结构的转换能力
- 嵌套 DTO 的层级表达
- 定长字符串区间切分
- 截断与拼接规划
- 复杂场景下的可扩展性
