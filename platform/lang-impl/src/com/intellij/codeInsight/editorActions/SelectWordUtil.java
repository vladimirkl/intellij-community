/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.editorActions;

import com.intellij.lang.ASTNode;
import com.intellij.lang.FileASTNode;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actions.EditorActionUtil;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.StringEscapesTokenTypes;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Mike
 */
public class SelectWordUtil {
  private static ExtendWordSelectionHandler[] SELECTIONERS = new ExtendWordSelectionHandler[]{
  };

  private static boolean ourExtensionsLoaded = false;

  private SelectWordUtil() {
  }

  /**
   * @see ExtendWordSelectionHandler#EP_NAME
   */
  @Deprecated
  public static void registerSelectioner(ExtendWordSelectionHandler selectioner) {
    SELECTIONERS = ArrayUtil.append(SELECTIONERS, selectioner);
  }

  static ExtendWordSelectionHandler[] getExtendWordSelectionHandlers() {
    if (!ourExtensionsLoaded) {
      ourExtensionsLoaded = true;
      for (ExtendWordSelectionHandler handler : Extensions.getExtensions(ExtendWordSelectionHandler.EP_NAME)) {
        registerSelectioner(handler);        
      }
    }
    return SELECTIONERS;
  }

  private static final CharCondition JAVA_IDENTIFIER_PART_CONDITION = new CharCondition() {
    @Override
    public boolean value(char ch) {
      return Character.isJavaIdentifierPart(ch);
    }
  };

  public static void addWordSelection(boolean camel, CharSequence editorText, int cursorOffset, @NotNull List<TextRange> ranges) {
    addWordSelection(camel, editorText, cursorOffset, ranges, JAVA_IDENTIFIER_PART_CONDITION);
  }

  public static void addWordSelection(boolean camel,
                                      CharSequence editorText,
                                      int cursorOffset,
                                      @NotNull List<TextRange> ranges,
                                      CharCondition isWordPartCondition) {
    TextRange camelRange = camel ? getCamelSelectionRange(editorText, cursorOffset, isWordPartCondition) : null;
    if (camelRange != null) {
      ranges.add(camelRange);
    }

    TextRange range = getWordSelectionRange(editorText, cursorOffset, isWordPartCondition);
    if (range != null && !range.equals(camelRange)) {
      ranges.add(range);
    }
  }

  @Nullable
  private static TextRange getCamelSelectionRange(CharSequence editorText, int cursorOffset, CharCondition isWordPartCondition) {
    if (cursorOffset < 0 || cursorOffset >= editorText.length()) {
      return null;
    }
    if (cursorOffset > 0 && !isWordPartCondition.value(editorText.charAt(cursorOffset)) &&
        isWordPartCondition.value(editorText.charAt(cursorOffset - 1))) {
      cursorOffset--;
    }

    if (isWordPartCondition.value(editorText.charAt(cursorOffset))) {
      int start = cursorOffset;
      int end = cursorOffset + 1;
      final int textLen = editorText.length();

      while (start > 0 && isWordPartCondition.value(editorText.charAt(start - 1)) && !EditorActionUtil.isHumpBound(editorText, start, true)) {
        start--;
      }

      while (end < textLen && isWordPartCondition.value(editorText.charAt(end)) && !EditorActionUtil.isHumpBound(editorText, end, false)) {
        end++;
      }

      if (start + 1 < end) {
        return new TextRange(start, end);
      }
    }

    return null;
  }

  @Nullable
  public static TextRange getWordSelectionRange(@NotNull CharSequence editorText, int cursorOffset, @NotNull CharCondition isWordPartCondition) {
    int length = editorText.length();
    if (length == 0) return null;
    if (cursorOffset == length ||
        cursorOffset > 0 && !isWordPartCondition.value(editorText.charAt(cursorOffset)) &&
        isWordPartCondition.value(editorText.charAt(cursorOffset - 1))) {
      cursorOffset--;
    }

    if (isWordPartCondition.value(editorText.charAt(cursorOffset))) {
      int start = cursorOffset;
      int end = cursorOffset;

      while (start > 0 && isWordPartCondition.value(editorText.charAt(start - 1))) {
        start--;
      }

      while (end < length && isWordPartCondition.value(editorText.charAt(end))) {
        end++;
      }

      return new TextRange(start, end);
    }

    return null;
  }

  public static void processRanges(@Nullable PsiElement element,
                                   CharSequence text,
                                   int cursorOffset,
                                   Editor editor,
                                   Processor<TextRange> consumer) {
    if (element == null) return;

    PsiFile file = element.getContainingFile();

    FileViewProvider viewProvider = file.getViewProvider();

    processInFile(element, consumer, text, cursorOffset, editor);

    for (PsiFile psiFile : viewProvider.getAllFiles()) {
      if (psiFile == file) continue;

      FileASTNode fileNode = psiFile.getNode();
      if (fileNode == null) continue;

      ASTNode nodeAt = fileNode.findLeafElementAt(element.getTextOffset());
      if (nodeAt == null) continue;

      PsiElement elementAt = nodeAt.getPsi();

      while (!(elementAt instanceof PsiFile) && elementAt != null) {
        if (elementAt.getTextRange().contains(element.getTextRange())) break;

        elementAt = elementAt.getParent();
      }

      if (elementAt == null) continue;

      processInFile(elementAt, consumer, text, cursorOffset, editor);
    }
  }

  private static void processInFile(@NotNull PsiElement element,
                                    Processor<TextRange> consumer,
                                    CharSequence text,
                                    int cursorOffset,
                                    Editor editor) {
    PsiElement e = element;
    while (e != null && !(e instanceof PsiFile)) {
      if (processElement(e, consumer, text, cursorOffset, editor)) return;
      e = e.getParent();
    }
  }

  private static boolean processElement(@NotNull PsiElement element,
                                     Processor<TextRange> processor,
                                     CharSequence text,
                                     int cursorOffset,
                                     Editor editor) {
    boolean stop = false;

    ExtendWordSelectionHandler[] extendWordSelectionHandlers = getExtendWordSelectionHandlers();
    int minimalTextRangeLength = 0;
    List<ExtendWordSelectionHandler> availableSelectioners = ContainerUtil.newLinkedList();
    for (ExtendWordSelectionHandler selectioner : extendWordSelectionHandlers) {
      if (selectioner.canSelect(element)) {
        int selectionerMinimalTextRange = selectioner instanceof ExtendWordSelectionHandlerBase
          ? ((ExtendWordSelectionHandlerBase)selectioner).getMinimalTextRangeLength(element, text, cursorOffset)
          : 0;
        minimalTextRangeLength = Math.max(minimalTextRangeLength, selectionerMinimalTextRange);
        availableSelectioners.add(selectioner);
      }
    }
    for (ExtendWordSelectionHandler selectioner : availableSelectioners) {
      List<TextRange> ranges = selectioner.select(element, text, cursorOffset, editor);
      if (ranges == null) continue;

      for (TextRange range : ranges) {
        if (range == null || range.getLength() < minimalTextRangeLength) continue;

        stop |= processor.process(range);
      }
    }

    return stop;
  }

  public static void addWordHonoringEscapeSequences(CharSequence editorText,
                                                    TextRange literalTextRange,
                                                    int cursorOffset,
                                                    Lexer lexer,
                                                    List<TextRange> result) {
    lexer.start(editorText, literalTextRange.getStartOffset(), literalTextRange.getEndOffset());

    while (lexer.getTokenType() != null) {
      if (lexer.getTokenStart() <= cursorOffset && cursorOffset < lexer.getTokenEnd()) {
        if (StringEscapesTokenTypes.STRING_LITERAL_ESCAPES.contains(lexer.getTokenType())) {
          result.add(new TextRange(lexer.getTokenStart(), lexer.getTokenEnd()));
        }
        else {
          TextRange word = getWordSelectionRange(editorText, cursorOffset, JAVA_IDENTIFIER_PART_CONDITION);
          if (word != null) {
            result.add(new TextRange(Math.max(word.getStartOffset(), lexer.getTokenStart()),
                                     Math.min(word.getEndOffset(), lexer.getTokenEnd())));
          }
        }
        break;
      }
      lexer.advance();
    }
  }
  
  public interface CharCondition { boolean value(char ch); }
}
