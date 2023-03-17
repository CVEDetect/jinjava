package com.hubspot.jinjava.util;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.hubspot.jinjava.el.ext.AbstractCallableMethod;
import com.hubspot.jinjava.interpret.Context;
import com.hubspot.jinjava.interpret.DeferredLazyReference;
import com.hubspot.jinjava.interpret.DeferredLazyReferenceSource;
import com.hubspot.jinjava.interpret.DeferredValue;
import com.hubspot.jinjava.interpret.DeferredValueMarker;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.tag.SetTag;
import com.hubspot.jinjava.lib.tag.eager.DeferredToken;
import com.hubspot.jinjava.tree.ExpressionNode;
import com.hubspot.jinjava.tree.Node;
import com.hubspot.jinjava.tree.TagNode;
import com.hubspot.jinjava.tree.TextNode;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DeferredValueUtils {
  private static final String TEMPLATE_TAG_REGEX = "(\\w+(?:\\.\\w+)*)";
  private static final Pattern TEMPLATE_TAG_PATTERN = Pattern.compile(TEMPLATE_TAG_REGEX);

  public static HashMap<String, Object> getDeferredContextWithOriginalValues(
    Map<String, Object> context
  ) {
    return getDeferredContextWithOriginalValues(context, ImmutableSet.of());
  }

  //The context needed for a second render
  //Ignores deferred properties with no originalValue
  //Optionally only keep keys in keysToKeep
  public static HashMap<String, Object> getDeferredContextWithOriginalValues(
    Map<String, Object> context,
    Set<String> keysToKeep
  ) {
    HashMap<String, Object> deferredContext = new HashMap<>(context.size());
    context.forEach(
      (contextKey, contextItem) -> {
        if (keysToKeep.size() > 0 && !keysToKeep.contains(contextKey)) {
          return;
        }
        if (contextItem instanceof DeferredValue) {
          if (((DeferredValue) contextItem).getOriginalValue() != null) {
            deferredContext.put(
              contextKey,
              ((DeferredValue) contextItem).getOriginalValue()
            );
          }
        }
      }
    );
    return deferredContext;
  }

  public static void deferVariables(String[] varTokens, Map<String, Object> context) {
    for (String varToken : varTokens) {
      String key = varToken.trim();
      Object originalValue = context.get(key);
      if (originalValue != null) {
        if (originalValue instanceof DeferredValue) {
          context.put(key, originalValue);
        } else {
          context.put(key, DeferredValue.instance(originalValue));
        }
      } else {
        context.put(key, DeferredValue.instance());
      }
    }
  }

  public static Set<String> findAndMarkDeferredProperties(Context context) {
    return findAndMarkDeferredProperties(context, null);
  }

  public static DeferredToken findAndMarkDeferredProperties2(
    Context context,
    DeferredToken deferredToken
  ) {
    Set<String> referentialDefers = new HashSet<>();
    Set<String> setProps = new HashSet<>();
    if (isInSameScope(context, deferredToken)) {
      setProps.addAll(
        getPropertiesUsedInDeferredNodes(
          context,
          deferredToken.getSetDeferredWords(),
          false
        )
      );
      referentialDefers.addAll(
        getPropertiesUsedInDeferredNodes(
          context,
          deferredToken.getUntouchedUsedDeferredWords(),
          true
        )
      );
    } else {
      List<String> macroArgs = deferredToken
        .getMacroStack()
        .peek()
        .map(
          name ->
            Optional
              .ofNullable(context.getGlobalMacro(name))
              .map(AbstractCallableMethod::getArguments)
              .orElseGet(
                () ->
                  context
                    .getLocalMacro(name)
                    .map(AbstractCallableMethod::getArguments)
                    .orElse(Collections.emptyList())
              )
        )
        .orElse(Collections.emptyList());
      // Filter out macro args because we will want them to be deferred on the higher-level contexts later
      referentialDefers.addAll(
        getPropertiesUsedInDeferredNodes(
            context,
            deferredToken.getUntouchedUsedDeferredWords(),
            true
          )
          .stream()
          .filter(prop -> !macroArgs.contains(prop))
          .collect(Collectors.toSet())
      );
    }
    //    deferredProps.addAll(referentialDefers);
    referentialDefers.forEach(
      word -> {
        Object wordValue = context.get(word);

        if (
          !(wordValue instanceof DeferredValue) &&
          !EagerExpressionResolver.isPrimitive(wordValue)
        ) {
          DeferredLazyReference deferredLazyReference = DeferredLazyReference.instance(
            context,
            word
          );
          Context temp = context;
          Set<Entry<String, Object>> matchingEntries = new HashSet<>();
          while (temp.getParent() != null) {
            temp
              .getScope()
              .entrySet()
              .stream()
              .filter(
                entry ->
                  entry.getValue() == wordValue ||
                  (
                    entry.getValue() instanceof DeferredValue &&
                    ((DeferredValue) entry.getValue()).getOriginalValue() == wordValue
                  )
              )
              .forEach(
                entry -> {
                  matchingEntries.add(entry);
                  deferredLazyReference
                    .getOriginalValue()
                    .setReferenceKey(entry.getKey());
                }
              );
            temp = temp.getParent();
          }
          if (matchingEntries.size() > 1) { // at least one duplicate
            matchingEntries.forEach(
              entry -> {
                if (
                  deferredLazyReference
                    .getOriginalValue()
                    .getReferenceKey()
                    .equals(entry.getKey())
                ) {
                  Object val = entry.getValue();
                  if (val instanceof DeferredLazyReferenceSource) {
                    return; // Already converted
                  }
                  DeferredLazyReferenceSource deferredLazyReferenceSource = DeferredLazyReferenceSource.instance(
                    val instanceof DeferredValue
                      ? ((DeferredValue) val).getOriginalValue()
                      : val
                  );

                  context.replace(entry.getKey(), deferredLazyReferenceSource);
                  entry.setValue(deferredLazyReferenceSource);
                } else {
                  entry.setValue(deferredLazyReference);
                }
              }
            );
          }
        }
      }
    );
    if (!referentialDefers.isEmpty()) {
      deferredToken
        .getUsedDeferredWords()
        .stream()
        .filter(
          key -> {
            Object val = context.getScope().get(key);
            return val != null && !(val instanceof DeferredValueMarker);
          }
          //            context.getScope().containsKey(key) &&
          //            !(context.getParent().getScope().get(key) instanceof DeferredValue)
        )
        .forEach(key -> deferredToken.getUntouchedUsedDeferredWords().remove(key));
      //      deferredToken =
      //        new DeferredToken(
      //          deferredToken.getToken(),
      //          deferredToken
      //            .getUsedDeferredWords()
      //            .stream()
      //            .filter(word -> !context.getScope().containsKey(word))
      //            .collect(Collectors.toSet()),
      //          deferredToken.getSetDeferredWords()
      //        );
    }

    markDeferredProperties(
      context,
      deferredToken,
      Sets.union(setProps, referentialDefers)
    );
    //    markDeferredProperties(context, referentialDefers);
    return deferredToken;
  }

  private static void markDeferredProperties(
    Context context,
    DeferredToken deferredToken,
    Set<String> wordsToDefer
  ) {
    wordsToDefer
      .stream()
      .filter(prop -> !(context.get(prop) instanceof DeferredValue))
      .filter(prop -> !context.getMetaContextVariables().contains(prop))
      .forEach(
        prop -> context.put(prop, convertToDeferredValue(context, deferredToken, prop))
      );
  }

  private static DeferredValue convertToDeferredValue(
    Context context,
    DeferredToken deferredToken,
    String prop
  ) {
    DeferredValue deferredValue = DeferredValue.instance();
    Object valueInScope = context.getScope().get(prop);
    Object value = context.get(prop);
    if (value != null) {
      if (
        (
          context.getParent() != null && isInSameScope(context.getParent(), deferredToken)
        ) ||
        valueInScope == null
      ) {
        deferredValue = DeferredValueMarker.instance(value);
      } else {
        deferredValue = DeferredValue.instance(value);
      }
    }
    return deferredValue;
  }

  private static boolean isInSameScope(Context context, DeferredToken deferredToken) {
    return (
      deferredToken.getMacroStack() == null ||
      deferredToken.getMacroStack() == context.getMacroStack()
    );
  }

  public static Set<String> findAndMarkDeferredProperties(
    Context context,
    DeferredToken deferredToken
  ) {
    String templateSource = rebuildTemplateForNodes(context.getDeferredNodes());
    Set<String> deferredProps = getPropertiesUsedInDeferredNodes(context, templateSource);
    Set<String> setProps = getPropertiesSetInDeferredNodes(templateSource);
    Set<String> referentialDefers = new HashSet<>();
    if (deferredToken != null) {
      if (isInSameScope(context, deferredToken)) {
        deferredProps.addAll(
          getPropertiesUsedInDeferredNodes(
            context,
            rebuildTemplateForEagerTagTokens(deferredToken, true),
            false
          )
        );
        referentialDefers.addAll(
          getPropertiesUsedInDeferredNodes(
            context,
            rebuildTemplateForEagerTagTokens(deferredToken, false),
            true
          )
        );
      } else {
        List<String> macroArgs = deferredToken
          .getMacroStack()
          .peek()
          .map(
            name ->
              Optional
                .ofNullable(context.getGlobalMacro(name))
                .map(AbstractCallableMethod::getArguments)
                .orElseGet(
                  () ->
                    context
                      .getLocalMacro(name)
                      .map(AbstractCallableMethod::getArguments)
                      .orElse(Collections.emptyList())
                )
          )
          .orElse(Collections.emptyList());
        // Filter out macro args because we will want them to be deferred on the higher-level contexts later
        referentialDefers.addAll(
          getPropertiesUsedInDeferredNodes(
              context,
              rebuildTemplateForEagerTagTokens(deferredToken, false),
              true
            )
            .stream()
            .filter(prop -> !macroArgs.contains(prop))
            .collect(Collectors.toSet())
        );
      }
    }
    deferredProps.addAll(referentialDefers);
    referentialDefers.forEach(
      word -> {
        Object wordValue = context.get(word);

        if (
          !(wordValue instanceof DeferredValue) &&
          !EagerExpressionResolver.isPrimitive(wordValue)
        ) {
          DeferredLazyReference deferredLazyReference = DeferredLazyReference.instance(
            context,
            word
          );
          Context temp = context;
          Set<Entry<String, Object>> matchingEntries = new HashSet<>();
          while (temp.getParent() != null) {
            temp
              .getScope()
              .entrySet()
              .stream()
              .filter(
                entry ->
                  entry.getValue() == wordValue ||
                  (
                    entry.getValue() instanceof DeferredLazyReferenceSource &&
                    ((DeferredLazyReferenceSource) entry.getValue()).getOriginalValue() ==
                    wordValue
                  )
              )
              .forEach(
                entry -> {
                  matchingEntries.add(entry);
                  deferredLazyReference
                    .getOriginalValue()
                    .setReferenceKey(entry.getKey());
                }
              );
            temp = temp.getParent();
          }
          if (matchingEntries.size() > 1) { // at least one duplicate
            matchingEntries.forEach(
              entry -> {
                if (
                  deferredLazyReference
                    .getOriginalValue()
                    .getReferenceKey()
                    .equals(entry.getKey())
                ) {
                  Object val = entry.getValue();
                  if (val instanceof DeferredLazyReferenceSource) {
                    return; // Already converted
                  }
                  context.replace(
                    entry.getKey(),
                    DeferredLazyReferenceSource.instance(val)
                  );
                  entry.setValue(DeferredLazyReferenceSource.instance(val));
                } else {
                  entry.setValue(deferredLazyReference);
                }
              }
            );
          }
        }
      }
    );

    markDeferredProperties(context, Sets.union(deferredProps, setProps));
    return deferredProps;
  }

  public static Set<String> getPropertiesSetInDeferredNodes(String templateSource) {
    return findSetProperties(templateSource);
  }

  public static Set<DeferredTag> getDeferredTags(Set<Node> deferredNodes) {
    return getDeferredTags(new LinkedList<>(deferredNodes), 0);
  }

  public static Set<String> getPropertiesUsedInDeferredNodes(
    Context context,
    String templateSource
  ) {
    return getPropertiesUsedInDeferredNodes(context, templateSource, true);
  }

  public static Set<String> getPropertiesUsedInDeferredNodes(
    Context context,
    String templateSource,
    boolean onlyAlreadyInContext
  ) {
    Stream<String> propertiesUsed = findUsedProperties(templateSource)
      .stream()
      .map(prop -> prop.split("\\.", 2)[0]); // split accesses on .prop
    if (onlyAlreadyInContext) {
      propertiesUsed = propertiesUsed.filter(context::containsKey);
    }
    return propertiesUsed.collect(Collectors.toSet());
  }

  public static Set<String> getPropertiesUsedInDeferredNodes(
    Context context,
    Set<String> words,
    boolean onlyAlreadyInContext
  ) {
    if (onlyAlreadyInContext) {
      return words.stream().filter(context::containsKey).collect(Collectors.toSet());
    }
    return words;
  }

  private static void markDeferredProperties(Context context, Set<String> props) {
    props
      .stream()
      .filter(prop -> !(context.get(prop) instanceof DeferredValue))
      .filter(prop -> !context.getMetaContextVariables().contains(prop))
      .forEach(
        prop -> {
          Object value = context.get(prop);
          if (value != null) {
            if (context.getScope().containsKey(prop)) {
              context.put(prop, DeferredValue.instance(value));
            } else {
              context.put(prop, DeferredValueMarker.instance(value));
            }
          } else {
            //Handle set props
            context.put(prop, DeferredValue.instance());
          }
        }
      );
  }

  private static Set<DeferredTag> getDeferredTags(List<Node> nodes, int depth) {
    // precaution - templates are parsed with this render depth so in theory the depth should never be exceeded
    Set<DeferredTag> deferredTags = new HashSet<>();
    int maxRenderDepth = JinjavaInterpreter.getCurrent() == null
      ? 3
      : JinjavaInterpreter.getCurrent().getConfig().getMaxRenderDepth();
    if (depth > maxRenderDepth) {
      return deferredTags;
    }
    for (Node node : nodes) {
      getDeferredTags(node).ifPresent(deferredTags::addAll);
      deferredTags.addAll(getDeferredTags(node.getChildren(), depth + 1));
    }
    return deferredTags;
  }

  private static String rebuildTemplateForNodes(Set<Node> nodes) {
    StringJoiner joiner = new StringJoiner(" ");
    getDeferredTags(nodes).stream().map(DeferredTag::getTag).forEach(joiner::add);
    return joiner.toString();
  }

  private static String rebuildTemplateForEagerTagTokens(
    DeferredToken deferredToken,
    boolean fromSetWords
  ) {
    StringJoiner joiner = new StringJoiner(" ");

    (
      fromSetWords
        ? deferredToken.getSetDeferredWords().stream()
        : deferredToken.getUsedDeferredWords().stream()
    ).map(h -> h + ".eager.helper")
      .forEach(joiner::add);
    return joiner.toString();
  }

  private static Set<String> findUsedProperties(String templateSource) {
    Matcher matcher = TEMPLATE_TAG_PATTERN.matcher(templateSource);
    Set<String> tags = Sets.newHashSet();
    while (matcher.find()) {
      tags.add(matcher.group(1));
    }
    return tags;
  }

  private static Set<String> findSetProperties(String templateSource) {
    Set<String> tags = Sets.newHashSet();
    String[] lines = templateSource.split("\n");
    for (String line : lines) {
      line = line.trim();
      if (line.contains(SetTag.TAG_NAME + " ")) {
        tags.addAll(findUsedProperties(line));
      }
    }

    return tags;
  }

  private static Optional<Set<DeferredTag>> getDeferredTags(Node deferredNode) {
    if (deferredNode instanceof TextNode || deferredNode.getMaster() == null) {
      return Optional.empty();
    }

    String nodeImage = deferredNode.getMaster().getImage();
    if (Strings.nullToEmpty(nodeImage).trim().isEmpty()) {
      return Optional.empty();
    }

    Set<DeferredTag> deferredTags = new HashSet<>();
    deferredTags.add(
      new DeferredTag().setTag(nodeImage).setNormalizedTag(getNormalizedTag(deferredNode))
    );

    if (deferredNode instanceof TagNode) {
      TagNode tagNode = (TagNode) deferredNode;
      if (tagNode.getEndName() != null) {
        String endTag = tagNode.reconstructEnd();
        deferredTags.add(new DeferredTag().setTag(endTag).setNormalizedTag(endTag));
      }
    }

    return Optional.of(deferredTags);
  }

  private static String getNormalizedTag(Node node) {
    if (node instanceof ExpressionNode) {
      return node.toString().replaceAll("\\s+", "");
    }

    return node.getMaster().getImage();
  }

  private static class DeferredTag {
    String tag;
    String normalizedTag;

    public String getTag() {
      return tag;
    }

    public DeferredTag setTag(String tag) {
      this.tag = tag;
      return this;
    }

    public String getNormalizedTag() {
      return normalizedTag;
    }

    public DeferredTag setNormalizedTag(String normalizedTag) {
      this.normalizedTag = normalizedTag;
      return this;
    }
  }
}
