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
 * Builds a DTO tree from graph-shaped metadata and maintains a skip-list-like
 * linked index over flattened leaf fields for fast range lookups.
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
    END_EXCLUSIVE,
    END_INCLUSIVE
  }

  public record JavaNode(
      String uuid,
      String nodeType,
      String propertyId,
      String dataType,
      PartsDto partsDto,
      PositionAtClass positionAtClass,
      String className) {

    public boolean isDtoClass() {
      return NODE_TYPE_DTO_CLASS.equals(nodeType);
    }

    public boolean isDtoField() {
      return NODE_TYPE_DTO_FIELD.equals(nodeType);
    }

    public boolean referencesDto() {
      return partsDto != null
          && partsDto.partsDtoUuid() != null
          && !partsDto.partsDtoUuid().isBlank();
    }
  }

  public record PartsDto(String partsDtoUuid, String partsDtoId) {
  }

  public record PositionAtClass(int startIndex, int endIndex) {
  }

  public record JavaEdge(String fromUuid, String toUuid, String edgeType) {
  }

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

    public int endExclusive() {
      return startIndex + len;
    }

    public boolean isLeaf() {
      return children.isEmpty();
    }
  }

  public record RangeMatch(
      Node sourceNode,
      int overlapStart,
      int overlapLen,
      int sourceClassOverlapStart,
      int sourceClassOverlapLen,
      int sourceOffset,
      int targetOffset) {

    public int overlapEndExclusive() {
      return overlapStart + overlapLen;
    }

    public int sourceClassOverlapEndExclusive() {
      return sourceClassOverlapStart + sourceClassOverlapLen;
    }
  }

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

    public List<Node> orderedLeafNodes() {
      return orderedLeafNodes;
    }

    public Node getNode(String uuid) {
      Node node = nodeByUuid.get(uuid);
      if (node == null) {
        throw new IllegalArgumentException("Node not found: " + uuid);
      }
      return node;
    }

    public List<RangeMatch> findSourceFields(int targetStart, int targetLen) {
      return rangeJumpTable.findOverlaps(targetStart, targetLen);
    }

    private AssignmentPlan buildAssignmentPlan(JavaNode targetNode, JavaNode sourceNode) {
      Objects.requireNonNull(targetNode, "targetNode must not be null");
      Objects.requireNonNull(sourceNode, "sourceNode must not be null");
      Node resolvedTargetNode = resolveTreeNode(targetNode);
      Node resolvedSourceNode = resolveTreeNode(sourceNode);
      return buildAssignmentPlanByAbsoluteOverlap(resolvedTargetNode, resolvedSourceNode);
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

      List<Node> sourceLeafNodes = new ArrayList<>();
      collectLeafNodes(resolvedSourceNode, sourceLeafNodes);
      sourceLeafNodes.sort(Comparator.comparingInt(Node::startIndex));

      List<RangeMatch> sourceWindowFragments = new RangeJumpTable(sourceLeafNodes)
          .findOverlaps(sourceBounds.absoluteStart(), sourceBounds.effectiveLen());
      List<RangeMatch> fragments = projectFragmentsToTargetWindow(sourceWindowFragments, targetBounds);
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

    private AssignmentPlan buildAssignmentPlanByAbsoluteOverlap(
        Node resolvedTargetNode,
        Node resolvedSourceNode) {
      List<Node> sourceLeafNodes = new ArrayList<>();
      collectLeafNodes(resolvedSourceNode, sourceLeafNodes);
      sourceLeafNodes.sort(Comparator.comparingInt(Node::startIndex));

      List<RangeMatch> fragments = new RangeJumpTable(sourceLeafNodes)
          .findOverlaps(resolvedTargetNode.startIndex(), resolvedTargetNode.len());
      int coveredLen = fragments.stream().mapToInt(RangeMatch::overlapLen).sum();
      boolean needsTruncation = fragments.stream()
          .anyMatch(fragment -> fragment.overlapLen() < fragment.sourceNode().len());
      boolean needsConcatenation = fragments.size() > 1;
      int missingLen = Math.max(0, resolvedTargetNode.len() - coveredLen);

      return new AssignmentPlan(
          resolvedTargetNode,
          resolvedSourceNode,
          fragments,
          resolvedTargetNode.len(),
          coveredLen,
          missingLen,
          false,
          0,
          false,
          0,
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

    private List<RangeMatch> projectFragmentsToTargetWindow(
        List<RangeMatch> sourceWindowFragments,
        WindowBounds targetBounds) {
      if (targetBounds.effectiveLen() <= 0 || sourceWindowFragments.isEmpty()) {
        return List.of();
      }

      List<RangeMatch> projectedFragments = new ArrayList<>();
      for (RangeMatch fragment : sourceWindowFragments) {
        int relativeStart = fragment.targetOffset();
        if (relativeStart >= targetBounds.effectiveLen()) {
          continue;
        }

        int projectedLen = Math.min(fragment.overlapLen(), targetBounds.effectiveLen() - relativeStart);
        if (projectedLen <= 0) {
          continue;
        }

        projectedFragments.add(new RangeMatch(
            fragment.sourceNode(),
            targetBounds.absoluteStart() + relativeStart,
            projectedLen,
            fragment.sourceClassOverlapStart(),
            projectedLen,
            fragment.sourceOffset(),
            relativeStart));
      }
      return List.copyOf(projectedFragments);
    }

    private WindowBounds resolveWindowBounds(Node node, WindowSlice window, String label) {
      if (window.offset() < 0) {
        throw new IllegalArgumentException(label + " offset must be >= 0");
      }
      if (window.len() < 0) {
        throw new IllegalArgumentException(label + " len must be >= 0");
      }

      int effectiveOffset = Math.min(window.offset(), node.len());
      int availableLen = Math.max(0, node.len() - effectiveOffset);
      int effectiveLen = Math.min(window.len(), availableLen);
      return new WindowBounds(
          window.offset(),
          window.len(),
          effectiveOffset,
          effectiveLen,
          node.startIndex() + effectiveOffset);
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

    private boolean insufficient() {
      return requestedOffset != effectiveOffset || requestedLen != effectiveLen;
    }

    private int missingLen() {
      return Math.max(0, requestedLen - effectiveLen);
    }
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
