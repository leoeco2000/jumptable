package org.springframework.boot.jumptable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 根据图结构元数据构建 DTO 树，并为叶子属性维护一份全局跳表索引，
 * 用于快速完成区间命中、截断和拼接规划。
 */
public class DtoTreeIndexBuilder {

  private static final String NODE_TYPE_DTO_CLASS = "DtoClass";
  private static final String NODE_TYPE_DTO_FIELD = "DtoField";
  private static final String FIELD_TYPE_DTO = "Dto";
  private static final String FIELD_TYPE_STRING = "String";

  private final RangeMode rangeMode;

  public DtoTreeIndexBuilder() {
    this(RangeMode.END_EXCLUSIVE);
  }

  public DtoTreeIndexBuilder(RangeMode rangeMode) {
    this.rangeMode = Objects.requireNonNull(rangeMode, "rangeMode must not be null");
  }

  /**
   * 将 `javaNodeList/javaEdgeList` 转为一棵 DTO 树。
   * 树节点同时保留两套坐标：
   * 一套是相对根节点的绝对偏移，一套是相对所属类的原始偏移。
   */
  public DtoIndexedTree build(List<JavaNode> javaNodeList, List<JavaEdge> javaEdgeList) {
    Objects.requireNonNull(javaNodeList, "javaNodeList must not be null");
    Objects.requireNonNull(javaEdgeList, "javaEdgeList must not be null");

    Map<String, JavaNode> allNodes = new HashMap<>();
    Map<String, JavaNode> classNodes = new HashMap<>();
    Map<String, JavaNode> fieldNodes = new HashMap<>();
    for (JavaNode node : javaNodeList) {
      if (node == null) {
        continue;
      }
      allNodes.put(node.uuid(), node);
      if (node.isDtoClass()) {
        classNodes.put(node.uuid(), node);
      } else if (node.isDtoField()) {
        fieldNodes.put(node.uuid(), node);
      }
    }

    Map<String, List<JavaNode>> classFields = mapClassFields(javaEdgeList, allNodes, fieldNodes);
    classFields.values().forEach(fields -> fields.sort(Comparator.comparingInt(this::fieldStart)));

    String rootClassUuid = resolveRootClass(classNodes.values(), fieldNodes.values());
    JavaNode rootClass = classNodes.get(rootClassUuid);
    if (rootClass == null) {
      throw new IllegalStateException("Root DTO class not found: " + rootClassUuid);
    }

    List<Node> leafNodes = new ArrayList<>();
    Node root = buildClassNode(rootClass, rootClass.className(), 0, classFields, classNodes, leafNodes);
    leafNodes.sort(Comparator.comparingInt(Node::startIndex));

    Map<String, String> classNameByUuid = new HashMap<>();
    classNodes.values().forEach(classNode -> classNameByUuid.put(classNode.uuid(), classNode.className()));
    return new DtoIndexedTree(root, leafNodes, new RangeJumpTable(leafNodes), classNameByUuid, allNodes);
  }

  private Map<String, List<JavaNode>> mapClassFields(
      List<JavaEdge> javaEdgeList,
      Map<String, JavaNode> allNodes,
      Map<String, JavaNode> fieldNodes) {

    Map<String, List<JavaNode>> classFields = new HashMap<>();
    for (JavaEdge edge : javaEdgeList) {
      if (edge == null) {
        continue;
      }
      JavaNode fromNode = allNodes.get(edge.fromUuid());
      JavaNode toNode = allNodes.get(edge.toUuid());
      if (fromNode == null || toNode == null) {
        continue;
      }

      JavaNode fieldNode = null;
      JavaNode classNode = null;
      if (fieldNodes.containsKey(fromNode.uuid()) && toNode.isDtoClass()) {
        fieldNode = fromNode;
        classNode = toNode;
      } else if (fieldNodes.containsKey(toNode.uuid()) && fromNode.isDtoClass()) {
        fieldNode = toNode;
        classNode = fromNode;
      }

      if (fieldNode != null && classNode != null) {
        classFields.computeIfAbsent(classNode.uuid(), ignored -> new ArrayList<>()).add(fieldNode);
      }
    }
    return classFields;
  }

  private String resolveRootClass(Collection<JavaNode> classNodes, Collection<JavaNode> fieldNodes) {
    Set<String> nestedClassUuids = new HashSet<>();
    for (JavaNode fieldNode : fieldNodes) {
      if (fieldNode.referencesDto()) {
        nestedClassUuids.add(fieldNode.partsDto().partsDtoUuid());
      }
    }

    List<String> rootCandidates = classNodes.stream()
        .map(JavaNode::uuid)
        .filter(uuid -> !nestedClassUuids.contains(uuid))
        .toList();

    if (rootCandidates.size() != 1) {
      throw new IllegalStateException(
          "Expected exactly one root DTO class, but found " + rootCandidates.size() + ": " + rootCandidates);
    }
    return rootCandidates.get(0);
  }

  private Node buildClassNode(
      JavaNode classNode,
      String fieldName,
      int baseStart,
      Map<String, List<JavaNode>> classFields,
      Map<String, JavaNode> classNodes,
      List<Node> leafNodes) {

    List<Node> children = new ArrayList<>();
    int maxEndExclusive = baseStart;

    for (JavaNode fieldNode : classFields.getOrDefault(classNode.uuid(), List.of())) {
      PositionAtClass positionAtClass = requirePosition(fieldNode);
      // 构树时统一把“相对所属类”的偏移换算成“相对根”的绝对偏移。
      int absoluteStart = baseStart + positionAtClass.startIndex();
      int len = fieldLength(fieldNode);
      maxEndExclusive = Math.max(maxEndExclusive, absoluteStart + len);

      if (fieldNode.referencesDto()) {
        JavaNode childClassNode = classNodes.get(fieldNode.partsDto().partsDtoUuid());
        if (childClassNode == null) {
          throw new IllegalStateException(
              "Nested DTO class not found for field " + fieldNode.propertyId() + ": "
                  + fieldNode.partsDto().partsDtoUuid());
        }
        Node child = buildClassNode(
            childClassNode,
            fieldNode.propertyId(),
            absoluteStart,
            classFields,
            classNodes,
            leafNodes);
        int dtoLen = Math.max(len, child.len());
        children.add(new Node(
            fieldNode.uuid(),
            FIELD_TYPE_DTO,
            fieldNode.propertyId(),
            absoluteStart,
            dtoLen,
            positionAtClass,
            childClassNode.uuid(),
            child.children()));
        maxEndExclusive = Math.max(maxEndExclusive, absoluteStart + dtoLen);
      } else {
        String fieldType = fieldNode.dataType() == null || fieldNode.dataType().isBlank()
            ? FIELD_TYPE_STRING
            : fieldNode.dataType();
        Node leaf = new Node(
            fieldNode.uuid(),
            fieldType,
            fieldNode.propertyId(),
            absoluteStart,
            len,
            positionAtClass,
            null,
            List.of());
        children.add(leaf);
        leafNodes.add(leaf);
      }
    }

    return new Node(
        classNode.uuid(),
        FIELD_TYPE_DTO,
        fieldName,
        baseStart,
        maxEndExclusive - baseStart,
        new PositionAtClass(0, Math.max(0, maxEndExclusive - baseStart)),
        classNode.uuid(),
        Collections.unmodifiableList(children));
  }

  private int fieldStart(JavaNode fieldNode) {
    return requirePosition(fieldNode).startIndex();
  }

  private int fieldLength(JavaNode fieldNode) {
    PositionAtClass position = requirePosition(fieldNode);
    return switch (rangeMode) {
      case END_EXCLUSIVE -> position.endIndex() - position.startIndex();
      case END_INCLUSIVE -> position.endIndex() - position.startIndex() + 1;
    };
  }

  private PositionAtClass requirePosition(JavaNode fieldNode) {
    PositionAtClass position = fieldNode.positionAtClass();
    if (position == null) {
      throw new IllegalStateException("Field positionAtClass is required: " + fieldNode.propertyId());
    }
    if (position.endIndex() < position.startIndex()) {
      throw new IllegalStateException("Field positionAtClass is invalid: " + fieldNode.propertyId());
    }
    return position;
  }

  public enum RangeMode {
    /** `endIndex` 按右开区间处理，即 `[startIndex, endIndex)`。 */
    END_EXCLUSIVE,
    /** `endIndex` 按闭区间处理，即 `[startIndex, endIndex]`。 */
    END_INCLUSIVE
  }

  /**
   * 原始图节点定义。
   *
   * @param uuid 节点唯一标识
   * @param nodeType 节点类型，当前支持 `DtoClass` 和 `DtoField`
   * @param propertyId 属性名；类节点时通常为空
   * @param dataType 属性数据类型
   * @param partsDto 当属性引用嵌套 DTO 时，指向被引用的 DTO 类
   * @param positionAtClass 节点相对所属类的原始区间
   * @param className 类名；属性节点时通常为空
   */
  public record JavaNode(
      String uuid,
      String nodeType,
      String propertyId,
      String dataType,
      PartsDto partsDto,
      PositionAtClass positionAtClass,
      String className) {

    /** 是否为 DTO 类节点。 */
    public boolean isDtoClass() {
      return NODE_TYPE_DTO_CLASS.equals(nodeType);
    }

    /** 是否为 DTO 属性节点。 */
    public boolean isDtoField() {
      return NODE_TYPE_DTO_FIELD.equals(nodeType);
    }

    /** 属性节点是否引用了嵌套 DTO。 */
    public boolean referencesDto() {
      return partsDto != null
          && partsDto.partsDtoUuid() != null
          && !partsDto.partsDtoUuid().isBlank();
    }
  }

  /**
   * 属性引用的嵌套 DTO 信息。
   *
   * @param partsDtoUuid 被引用 DTO 类的 uuid
   * @param partsDtoId 被引用 DTO 的业务标识
   */
  public record PartsDto(String partsDtoUuid, String partsDtoId) {
  }

  /**
   * 节点相对所属类的区间。
   *
   * @param startIndex 起始偏移
   * @param endIndex 结束偏移，语义由 `RangeMode` 决定
   */
  public record PositionAtClass(int startIndex, int endIndex) {
  }

  /**
   * 图中的边定义。
   *
   * @param fromUuid 起点节点 uuid
   * @param toUuid 终点节点 uuid
   * @param edgeType 边类型
   */
  public record JavaEdge(String fromUuid, String toUuid, String edgeType) {
  }

  /**
   * DTO 树节点。
   *
   * @param uuid 节点唯一标识；类节点与属性节点都复用原始节点 uuid
   * @param fieldType 字段类型；DTO 节点固定为 `Dto`
   * @param fieldName 字段名或类名
   * @param startIndex 相对根节点的绝对起始偏移
   * @param len 节点长度
   * @param positionAtClass 相对所属类的原始区间
   * @param dtoClassUuid 当节点表示 DTO 时，对应 DTO 类的 uuid
   * @param children 子节点列表
   */
  public record Node(
      String uuid,
      String fieldType,
      String fieldName,
      int startIndex,
      int len,
      PositionAtClass positionAtClass,
      String dtoClassUuid,
      List<Node> children) {

    public Node {
      children = children == null ? List.of() : List.copyOf(children);
    }

    /** 返回右开区间结束位置。 */
    public int endExclusive() {
      return startIndex + len;
    }

    /** 判断当前节点是否为叶子属性。 */
    public boolean isLeaf() {
      return children.isEmpty();
    }
  }

  /**
   * 区间命中结果。
   *
   * @param sourceNode 命中的源叶子属性
   * @param overlapStart 命中区间在根坐标系下的绝对起点
   * @param overlapLen 命中长度
   * @param sourceClassOverlapStart 命中区间在源属性所属类坐标系下的起点
   * @param sourceClassOverlapLen 命中区间在源属性所属类坐标系下的长度
   * @param sourceOffset 命中区间相对源属性自身起点的偏移
   * @param targetOffset 命中区间相对目标窗口起点的偏移
   */
  public record RangeMatch(
      Node sourceNode,
      int overlapStart,
      int overlapLen,
      int sourceClassOverlapStart,
      int sourceClassOverlapLen,
      int sourceOffset,
      int targetOffset) {

    /** 返回根坐标系下命中区间的结束位置。 */
    public int overlapEndExclusive() {
      return overlapStart + overlapLen;
    }

    /** 返回源属性所属类坐标系下命中区间的结束位置。 */
    public int sourceClassOverlapEndExclusive() {
      return sourceClassOverlapStart + sourceClassOverlapLen;
    }
  }

  /** DTO 树及其配套索引。 */
  public static final class DtoIndexedTree {

    private final Node root;
    private final Map<String, Node> nodeByUuid;
    private final Map<String, String> parentNodeUuidByNodeUuid;
    private final Map<String, List<Node>> dtoNodesByClassUuid;
    private final Map<String, String> classNameByUuid;
    private final Map<String, JavaNode> originalNodeByUuid;
    private final List<Node> orderedLeafNodes;
    private final RangeJumpTable rangeJumpTable;

    private DtoIndexedTree(
        Node root,
        List<Node> orderedLeafNodes,
        RangeJumpTable rangeJumpTable,
        Map<String, String> classNameByUuid,
        Map<String, JavaNode> originalNodeByUuid) {
      this.root = root;
      this.nodeByUuid = indexNodes(root);
      this.parentNodeUuidByNodeUuid = indexParentNodeUuids(root);
      this.dtoNodesByClassUuid = indexDtoNodes(root);
      this.classNameByUuid = Map.copyOf(classNameByUuid);
      this.originalNodeByUuid = Map.copyOf(originalNodeByUuid);
      this.orderedLeafNodes = List.copyOf(orderedLeafNodes);
      this.rangeJumpTable = rangeJumpTable;
    }

    public Node root() {
      return root;
    }

    /** 返回按绝对偏移排序后的全部叶子属性。 */
    public List<Node> orderedLeafNodes() {
      return orderedLeafNodes;
    }

    /** 按树节点 uuid 获取构建后的节点。 */
    public Node getNode(String uuid) {
      Node node = nodeByUuid.get(uuid);
      if (node == null) {
        throw new IllegalArgumentException("Node not found: " + uuid);
      }
      return node;
    }

    /**
     * 直接按根坐标系下的绝对区间做查询。
     * 这是底层能力，保留给已经完成坐标换算的调用方使用。
     */
    public List<RangeMatch> findSourceFields(int targetStart, int targetLen) {
      return rangeJumpTable.findOverlaps(targetStart, targetLen);
    }

    /** 按节点自身区间查询，参数节点可以是类或属性。 */
    public List<RangeMatch> findSourceFields(String targetUuid) {
      Objects.requireNonNull(targetUuid, "targetUuid must not be null");
      return findSourceFields(getOriginalNode(targetUuid));
    }

    /** 按节点自身区间查询，参数节点可以是类或属性。 */
    public List<RangeMatch> findSourceFields(JavaNode targetNode) {
      Objects.requireNonNull(targetNode, "targetNode must not be null");
      Node resolvedTargetNode = resolveTreeNode(targetNode);
      return findSourceFields(
          resolvedTargetNode,
          new WindowSlice(defaultWindowStart(resolvedTargetNode), resolvedTargetNode.len()));
    }

    /** 按“相对所属类”的窗口区间查询。 */
    public List<RangeMatch> findSourceFields(String targetUuid, int targetOffset, int targetLen) {
      Objects.requireNonNull(targetUuid, "targetUuid must not be null");
      return findSourceFields(getOriginalNode(targetUuid), targetOffset, targetLen);
    }

    /** 按“相对所属类”的窗口区间查询。 */
    public List<RangeMatch> findSourceFields(JavaNode targetNode, int targetOffset, int targetLen) {
      Objects.requireNonNull(targetNode, "targetNode must not be null");
      Node resolvedTargetNode = resolveTreeNode(targetNode);
      return findSourceFields(resolvedTargetNode, new WindowSlice(targetOffset, targetLen));
    }

    private List<RangeMatch> findSourceFields(Node resolvedTargetNode, WindowSlice targetWindow) {
      WindowBounds targetBounds = resolveWindowBounds(resolvedTargetNode, targetWindow, "target");
      // 先把所属类坐标系下的窗口换算成绝对区间，再复用全局跳表查询。
      return alignMatchesToTargetWindow(
          rangeJumpTable.findOverlaps(targetBounds.absoluteStart(), targetBounds.effectiveLen()),
          targetBounds);
    }

    private AssignmentPlan buildAssignmentPlan(JavaNode targetNode, JavaNode sourceNode) {
      Objects.requireNonNull(targetNode, "targetNode must not be null");
      Objects.requireNonNull(sourceNode, "sourceNode must not be null");
      Node resolvedTargetNode = resolveTreeNode(targetNode);
      Node resolvedSourceNode = resolveTreeNode(sourceNode);
      return buildAssignmentPlan(
          resolvedTargetNode,
          new WindowSlice(defaultWindowStart(resolvedTargetNode), resolvedTargetNode.len()),
          resolvedSourceNode,
          new WindowSlice(defaultWindowStart(resolvedSourceNode), resolvedSourceNode.len()));
    }

    private AssignmentPlan buildAssignmentPlan(
        JavaNode targetNode,
        int targetOffset,
        int targetLen,
        JavaNode sourceNode,
        int sourceOffset,
        int sourceLen) {
      Objects.requireNonNull(targetNode, "targetNode must not be null");
      Objects.requireNonNull(sourceNode, "sourceNode must not be null");

      Node resolvedTargetNode = resolveTreeNode(targetNode);
      Node resolvedSourceNode = resolveTreeNode(sourceNode);
      return buildAssignmentPlan(
          resolvedTargetNode,
          new WindowSlice(targetOffset, targetLen),
          resolvedSourceNode,
          new WindowSlice(sourceOffset, sourceLen));
    }

    private AssignmentPlan buildAssignmentPlan(
        Node resolvedTargetNode,
        WindowSlice targetWindow,
        Node resolvedSourceNode,
        WindowSlice sourceWindow) {
      WindowBounds targetBounds = resolveWindowBounds(resolvedTargetNode, targetWindow, "target");
      WindowBounds sourceBounds = resolveWindowBounds(resolvedSourceNode, sourceWindow, "source");
      // 目标窗口和源窗口都先换算成绝对区间，再取交集作为真正的赋值区间。
      AbsoluteRange overlapRange = intersectAbsoluteRanges(targetBounds, sourceBounds);

      List<RangeMatch> sourceWindowFragments = filterSourceSubtreeMatches(
          rangeJumpTable.findOverlaps(overlapRange.start(), overlapRange.len()),
          resolvedSourceNode);
      List<RangeMatch> fragments = alignMatchesToTargetWindow(sourceWindowFragments, targetBounds);
      int coveredLen = fragments.stream().mapToInt(RangeMatch::overlapLen).sum();
      boolean needsTruncation = fragments.stream()
          .anyMatch(fragment -> fragment.overlapLen() < fragment.sourceNode().len());
      boolean needsConcatenation = fragments.size() > 1;
      int missingLen = Math.max(0, targetBounds.effectiveLen() - coveredLen);

      return new AssignmentPlan(
          resolvedTargetNode,
          resolvedSourceNode,
          fragments,
          targetBounds.effectiveLen(),
          coveredLen,
          missingLen,
          targetBounds.insufficient(),
          targetBounds.missingLen(),
          sourceBounds.insufficient(),
          sourceBounds.missingLen(),
          needsTruncation,
          needsConcatenation);
    }

    public List<AssignmentSourceProperty> listSourceAssignmentProperties(
        String targetFieldUuid,
        JavaNode sourceClassNode) {
      return listSourceAssignmentProperties(getOriginalNode(targetFieldUuid), sourceClassNode);
    }

    public List<AssignmentSourceProperty> listSourceAssignmentProperties(
        String targetUuid,
        String sourceUuid) {
      Objects.requireNonNull(targetUuid, "targetUuid must not be null");
      Objects.requireNonNull(sourceUuid, "sourceUuid must not be null");
      return listSourceAssignmentProperties(getOriginalNode(targetUuid), getOriginalNode(sourceUuid));
    }

    public List<AssignmentSourceProperty> listSourceAssignmentProperties(
        JavaNode targetNode,
        JavaNode sourceNode) {
      AssignmentPlan plan = buildAssignmentPlan(targetNode, sourceNode);
      return buildAssignmentSourceProperties(plan);
    }

    public List<AssignmentSourceProperty> listSourceAssignmentProperties(
        String targetUuid,
        int targetOffset,
        int targetLen,
        String sourceUuid,
        int sourceOffset,
        int sourceLen) {
      Objects.requireNonNull(targetUuid, "targetUuid must not be null");
      Objects.requireNonNull(sourceUuid, "sourceUuid must not be null");
      return listSourceAssignmentProperties(
          getOriginalNode(targetUuid),
          targetOffset,
          targetLen,
          getOriginalNode(sourceUuid),
          sourceOffset,
          sourceLen);
    }

    public List<AssignmentSourceProperty> listSourceAssignmentProperties(
        JavaNode targetNode,
        int targetOffset,
        int targetLen,
        JavaNode sourceNode,
        int sourceOffset,
        int sourceLen) {
      AssignmentPlan plan = buildAssignmentPlan(
          targetNode,
          targetOffset,
          targetLen,
          sourceNode,
          sourceOffset,
          sourceLen);
      return buildAssignmentSourceProperties(plan);
    }

    private List<AssignmentSourceProperty> buildAssignmentSourceProperties(AssignmentPlan plan) {
      List<AssignmentSourceProperty> properties = new ArrayList<>();
      for (RangeMatch fragment : plan.sourceFragments()) {
        boolean truncated = fragment.overlapLen() < fragment.sourceNode().len();
        properties.add(new AssignmentSourceProperty(
            fragment.sourceNode().uuid(),
            fragment.sourceNode().fieldName(),
            buildPathKeyValues(fragment.sourceNode()),
            plan.targetInsufficient(),
            plan.targetMissingLen(),
            plan.sourceInsufficient(),
            plan.sourceMissingLen(),
            truncated,
            truncated ? fragment.sourceOffset() : 0,
            truncated ? fragment.overlapLen() : 0));
      }
      return List.copyOf(properties);
    }

    private AbsoluteRange intersectAbsoluteRanges(
        WindowBounds targetBounds,
        WindowBounds sourceBounds) {
      int overlapStart = Math.max(targetBounds.absoluteStart(), sourceBounds.absoluteStart());
      int overlapEnd = Math.min(targetBounds.endExclusive(), sourceBounds.endExclusive());
      return new AbsoluteRange(overlapStart, Math.max(0, overlapEnd - overlapStart));
    }

    private List<RangeMatch> alignMatchesToTargetWindow(
        List<RangeMatch> sourceWindowFragments,
        WindowBounds targetBounds) {
      if (sourceWindowFragments.isEmpty()) {
        return List.of();
      }

      List<RangeMatch> alignedFragments = new ArrayList<>();
      for (RangeMatch fragment : sourceWindowFragments) {
        // 跳表返回的是绝对区间命中结果，这里补齐“相对目标窗口”的偏移信息。
        alignedFragments.add(new RangeMatch(
            fragment.sourceNode(),
            fragment.overlapStart(),
            fragment.overlapLen(),
            fragment.sourceClassOverlapStart(),
            fragment.sourceClassOverlapLen(),
            fragment.sourceOffset(),
            fragment.overlapStart() - targetBounds.absoluteStart()));
      }
      return List.copyOf(alignedFragments);
    }

    private List<RangeMatch> filterSourceSubtreeMatches(
        List<RangeMatch> candidateMatches,
        Node sourceRoot) {
      List<RangeMatch> filteredMatches = new ArrayList<>();
      for (RangeMatch candidateMatch : candidateMatches) {
        // 全局跳表只能先找出区间候选，再通过父子链过滤到源子树内部。
        if (isDescendantOrSelf(candidateMatch.sourceNode().uuid(), sourceRoot.uuid())) {
          filteredMatches.add(candidateMatch);
        }
      }
      return List.copyOf(filteredMatches);
    }

    private boolean isDescendantOrSelf(String nodeUuid, String ancestorUuid) {
      String currentUuid = nodeUuid;
      while (currentUuid != null) {
        if (ancestorUuid.equals(currentUuid)) {
          return true;
        }
        currentUuid = parentNodeUuidByNodeUuid.get(currentUuid);
      }
      return false;
    }

    private WindowBounds resolveWindowBounds(Node node, WindowSlice window, String label) {
      if (window.offset() < 0) {
        throw new IllegalArgumentException(label + " offset must be >= 0");
      }
      if (window.len() < 0) {
        throw new IllegalArgumentException(label + " len must be >= 0");
      }

      int nodeLocalStart = defaultWindowStart(node);
      int nodeLocalEnd = nodeLocalStart + node.len();
      int requestedStart = window.offset();
      int requestedEnd = requestedStart + window.len();
      int effectiveStart = Math.max(requestedStart, nodeLocalStart);
      int effectiveEnd = Math.min(requestedEnd, nodeLocalEnd);
      int effectiveLen = Math.max(0, effectiveEnd - effectiveStart);
      // 方法参数的 offset/len 统一按“相对所属类”解释，这里完成局部区间裁剪和绝对化。
      return new WindowBounds(
          requestedStart,
          window.len(),
          effectiveStart,
          effectiveLen,
          ownerAbsoluteStart(node) + effectiveStart);
    }

    private int defaultWindowStart(Node node) {
      return node.positionAtClass().startIndex();
    }

    /** 通过“绝对起点 - 相对所属类起点”反推出所属类在根坐标系下的起点。 */
    private int ownerAbsoluteStart(Node node) {
      return node.startIndex() - node.positionAtClass().startIndex();
    }

    private JavaNode getOriginalNode(String uuid) {
      JavaNode javaNode = originalNodeByUuid.get(uuid);
      if (javaNode == null) {
        throw new IllegalArgumentException("Original JavaNode not found: " + uuid);
      }
      return javaNode;
    }

    private Node resolveTreeNode(JavaNode javaNode) {
      if (javaNode.isDtoClass()) {
        return resolveDtoClassNode(javaNode.uuid());
      }
      if (javaNode.isDtoField()) {
        return getNode(javaNode.uuid());
      }
      throw new IllegalArgumentException("Unsupported JavaNode type: " + javaNode.nodeType());
    }

    private Node resolveDtoClassNode(String classUuid) {
      List<Node> matchedNodes = dtoNodesByClassUuid.getOrDefault(classUuid, List.of());
      if (matchedNodes.isEmpty()) {
        throw new IllegalArgumentException("DTO class node not materialized in tree: " + classUuid);
      }
      if (matchedNodes.size() > 1) {
        throw new IllegalArgumentException(
            "DTO class node is materialized multiple times, source class is ambiguous: " + classUuid);
      }
      return matchedNodes.get(0);
    }

    private Map<String, Node> indexNodes(Node root) {
      Map<String, Node> indexedNodes = new HashMap<>();
      indexNodes(root, indexedNodes);
      return Map.copyOf(indexedNodes);
    }

    private Map<String, String> indexParentNodeUuids(Node root) {
      Map<String, String> indexedParents = new HashMap<>();
      indexParentNodeUuids(root, null, indexedParents);
      return Map.copyOf(indexedParents);
    }

    private Map<String, List<Node>> indexDtoNodes(Node root) {
      Map<String, List<Node>> indexedDtoNodes = new HashMap<>();
      indexDtoNodes(root, indexedDtoNodes);
      Map<String, List<Node>> immutableIndexedDtoNodes = new HashMap<>();
      indexedDtoNodes.forEach((uuid, nodes) -> immutableIndexedDtoNodes.put(uuid, List.copyOf(nodes)));
      return Map.copyOf(immutableIndexedDtoNodes);
    }

    private void indexNodes(Node node, Map<String, Node> indexedNodes) {
      indexedNodes.put(node.uuid(), node);
      for (Node child : node.children()) {
        indexNodes(child, indexedNodes);
      }
    }

    private void indexParentNodeUuids(Node node, Node parent, Map<String, String> indexedParents) {
      if (parent != null) {
        indexedParents.put(node.uuid(), parent.uuid());
      }
      for (Node child : node.children()) {
        indexParentNodeUuids(child, node, indexedParents);
      }
    }

    private void indexDtoNodes(Node node, Map<String, List<Node>> indexedDtoNodes) {
      if (FIELD_TYPE_DTO.equals(node.fieldType()) && node.dtoClassUuid() != null) {
        indexedDtoNodes.computeIfAbsent(node.dtoClassUuid(), ignored -> new ArrayList<>()).add(node);
      }
      for (Node child : node.children()) {
        indexDtoNodes(child, indexedDtoNodes);
      }
    }

    private void collectLeafNodes(Node node, List<Node> leafNodes) {
      if (node.isLeaf()) {
        leafNodes.add(node);
        return;
      }
      for (Node child : node.children()) {
        collectLeafNodes(child, leafNodes);
      }
    }

    private List<Map<String, Object>> buildPathKeyValues(Node leafNode) {
      List<Node> pathNodes = buildPathNodes(leafNode.uuid());
      List<Map<String, Object>> keyValues = new ArrayList<>();
      Node rootNode = pathNodes.get(0);
      appendPathNode(keyValues, rootNode.dtoClassUuid(), NODE_TYPE_DTO_CLASS,
          classNameByUuid.get(rootNode.dtoClassUuid()), originalNodeByUuid.get(rootNode.dtoClassUuid()));

      for (int i = 1; i < pathNodes.size(); i++) {
        Node pathNode = pathNodes.get(i);
        JavaNode propertyNode = originalNodeByUuid.get(pathNode.uuid());
        appendPathNode(
            keyValues,
            pathNode.uuid(),
            propertyNode == null ? NODE_TYPE_DTO_FIELD : propertyNode.nodeType(),
            pathNode.fieldName(),
            propertyNode);
        if (FIELD_TYPE_DTO.equals(pathNode.fieldType())) {
          appendPathNode(
              keyValues,
              pathNode.dtoClassUuid(),
              NODE_TYPE_DTO_CLASS,
              classNameByUuid.get(pathNode.dtoClassUuid()),
              originalNodeByUuid.get(pathNode.dtoClassUuid()));
        }
      }
      return List.copyOf(keyValues);
    }

    /** 从叶子节点反向回溯到根节点，构造完整路径。 */
    private List<Node> buildPathNodes(String nodeUuid) {
      List<Node> reversedPath = new ArrayList<>();
      String currentUuid = nodeUuid;
      while (currentUuid != null) {
        Node currentNode = nodeByUuid.get(currentUuid);
        if (currentNode == null) {
          throw new IllegalStateException("Path node not found: " + currentUuid);
        }
        reversedPath.add(currentNode);
        currentUuid = parentNodeUuidByNodeUuid.get(currentUuid);
      }
      Collections.reverse(reversedPath);
      return List.copyOf(reversedPath);
    }

    private void appendPathNode(
        List<Map<String, Object>> keyValues,
        String uuid,
        String type,
        String name,
        JavaNode javaNode) {
      Map<String, Object> pathNode = new LinkedHashMap<>();
      pathNode.put("uuid", uuid);
      pathNode.put("type", type);
      pathNode.put("name", name);
      pathNode.put("javaNode", javaNode);
      keyValues.add(Map.copyOf(pathNode));
    }
  }

  private record WindowSlice(int offset, int len) {
  }

  private record WindowBounds(
      int requestedOffset,
      int requestedLen,
      int effectiveOffset,
      int effectiveLen,
      int absoluteStart) {

    private int endExclusive() {
      return absoluteStart + effectiveLen;
    }

    private boolean insufficient() {
      return requestedOffset != effectiveOffset || requestedLen != effectiveLen;
    }

    private int missingLen() {
      return Math.max(0, requestedLen - effectiveLen);
    }
  }

  private record AbsoluteRange(int start, int len) {
  }

  private record AssignmentPlan(
      Node targetNode,
      Node sourceClassNode,
      List<RangeMatch> sourceFragments,
      int targetLen,
      int coveredLen,
      int missingLen,
      boolean targetInsufficient,
      int targetMissingLen,
      boolean sourceInsufficient,
      int sourceMissingLen,
      boolean needsTruncation,
      boolean needsConcatenation) {

    public AssignmentPlan {
      sourceFragments = sourceFragments == null ? List.of() : List.copyOf(sourceFragments);
    }

    public boolean isComplete() {
      return missingLen == 0;
    }

    public boolean needsTransform() {
      return needsTruncation || needsConcatenation || missingLen > 0;
    }
  }

  /**
   * 对外暴露的源属性赋值结果。
   *
   * @param propertyUuid 源属性 uuid
   * @param propertyName 源属性名
   * @param pathKeyValues 从根到当前属性的路径信息
   * @param targetInsufficient 目标窗口是否不足
   * @param targetMissingLen 目标窗口不足的长度
   * @param sourceInsufficient 源窗口是否不足
   * @param sourceMissingLen 源窗口不足的长度
   * @param needTruncate 当前源属性是否需要截断
   * @param truncateOffset 截断起始偏移；无截断时为 0
   * @param truncateLen 截断长度；无截断时为 0
   */
  public record AssignmentSourceProperty(
      String propertyUuid,
      String propertyName,
      List<Map<String, Object>> pathKeyValues,
      boolean targetInsufficient,
      int targetMissingLen,
      boolean sourceInsufficient,
      int sourceMissingLen,
      boolean needTruncate,
      int truncateOffset,
      int truncateLen) {

    public AssignmentSourceProperty {
      pathKeyValues = pathKeyValues == null ? List.of() : List.copyOf(pathKeyValues);
    }
  }

  /** 基于叶子属性绝对区间的跳表索引。 */
  public static final class RangeJumpTable {

    private final SegmentLink head;
    private final int maxLevel;

    RangeJumpTable(List<Node> orderedLeafNodes) {
      Objects.requireNonNull(orderedLeafNodes, "orderedLeafNodes must not be null");
      if (orderedLeafNodes.isEmpty()) {
        this.maxLevel = 1;
        this.head = new SegmentLink(null, 1);
        return;
      }

      this.maxLevel = 32 - Integer.numberOfLeadingZeros(orderedLeafNodes.size());
      List<SegmentLink> links = new ArrayList<>(orderedLeafNodes.size());
      for (Node node : orderedLeafNodes) {
        links.add(new SegmentLink(node, maxLevel));
      }
      for (int i = 0; i < links.size() - 1; i++) {
        links.get(i).next = links.get(i + 1);
      }

      this.head = new SegmentLink(null, maxLevel);
      this.head.next = links.get(0);
      this.head.forward[0] = links.get(0);

      for (int level = 1; level < maxLevel; level++) {
        int headIndex = (1 << level) - 1;
        if (headIndex < links.size()) {
          this.head.forward[level] = links.get(headIndex);
        }
      }

      for (int i = 0; i < links.size(); i++) {
        SegmentLink current = links.get(i);
        current.forward[0] = current.next;
        for (int level = 1; level < maxLevel; level++) {
          int jumpIndex = i + (1 << level);
          if (jumpIndex < links.size()) {
            current.forward[level] = links.get(jumpIndex);
          }
        }
      }
    }

    /**
     * 查询与目标绝对区间有重叠的全部叶子属性。
     * 返回结果按绝对起点有序排列。
     */
    public List<RangeMatch> findOverlaps(int targetStart, int targetLen) {
      if (targetLen <= 0 || head.next == null) {
        return List.of();
      }

      int targetEndExclusive = targetStart + targetLen;
      SegmentLink cursor = head;
      for (int level = maxLevel - 1; level >= 0; level--) {
        SegmentLink next = cursor.forward[level];
        while (next != null && next.endExclusive() <= targetStart) {
          cursor = next;
          next = cursor.forward[level];
        }
      }

      SegmentLink current = cursor == head ? head.next : cursor.next;
      List<RangeMatch> matches = new ArrayList<>();
      // 定位到第一个可能相交的节点后，顺着单链表向后扫描即可。
      while (current != null && current.startIndex() < targetEndExclusive) {
        int overlapStart = Math.max(current.startIndex(), targetStart);
        int overlapEndExclusive = Math.min(current.endExclusive(), targetEndExclusive);
        if (overlapStart < overlapEndExclusive) {
          int overlapLen = overlapEndExclusive - overlapStart;
          int sourceOffset = overlapStart - current.startIndex();
          matches.add(new RangeMatch(
              current.node,
              overlapStart,
              overlapLen,
              current.node.positionAtClass().startIndex() + sourceOffset,
              overlapLen,
              sourceOffset,
              overlapStart - targetStart));
        }
        current = current.next;
      }
      return List.copyOf(matches);
    }

    private static final class SegmentLink {

      private final Node node;
      private final SegmentLink[] forward;
      private SegmentLink next;

      private SegmentLink(Node node, int maxLevel) {
        this.node = node;
        this.forward = new SegmentLink[maxLevel];
      }

      private int startIndex() {
        return node == null ? Integer.MIN_VALUE : node.startIndex();
      }

      private int endExclusive() {
        return node == null ? Integer.MIN_VALUE : node.endExclusive();
      }
    }
  }
}
