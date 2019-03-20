/*******************************************************************************
 * Copyright (c) 2019 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.handlers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation.IChooseImportQuery;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.internal.corext.dom.IASTSharedValues;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.text.correction.SourceAssistProcessor;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.text.edits.TextEdit;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class OrganizeImportsHandler {

	public static TextEdit organizeImports(ICompilationUnit unit, BiFunction<ImportChoice[][], Range[], ImportChoice[]> chooseImport) {
		if (unit == null) {
			return null;
		}

		RefactoringASTParser astParser = new RefactoringASTParser(IASTSharedValues.SHARED_AST_LEVEL);
		CompilationUnit astRoot = astParser.parse(unit, true);
		OrganizeImportsOperation op = new OrganizeImportsOperation(unit, astRoot, true, false, true, new IChooseImportQuery() {

			@Override
			public TypeNameMatch[] chooseImports(TypeNameMatch[][] openChoices, ISourceRange[] ranges) {
				ImportChoice[][] clientImportChoices = Stream.of(openChoices).map((choices) -> {
					return Stream.of(choices).map((choice) -> new ImportChoice(choice)).toArray(ImportChoice[]::new);
				}).toArray(ImportChoice[][]::new);

				Range[] clientRanges = Stream.of(ranges).map((range) -> {
					try {
						return JDTUtils.toRange(unit, range.getOffset(), range.getLength());
					} catch (JavaModelException e) {
						return JDTUtils.newRange();
					}
				}).toArray(Range[]::new);

				ImportChoice[] chosens = chooseImport.apply(clientImportChoices, clientRanges);
				if (chosens == null) {
					return null;
				}

				Map<String, TypeNameMatch> typeMaps = new HashMap<>();
				Stream.of(openChoices).flatMap(x -> Arrays.stream(x)).forEach(x -> {
					typeMaps.put(x.getFullyQualifiedName() + "@" + x.hashCode(), x);
				});
				return Stream.of(chosens).filter(chosen -> chosen != null && typeMaps.containsKey(chosen.id)).map(chosen -> typeMaps.get(chosen.id)).toArray(TypeNameMatch[]::new);
			}
		});

		try {
			return op.createTextEdit(null);
		} catch (OperationCanceledException | CoreException e) {
			JavaLanguageServerPlugin.logException("Resolve organize imports source action", e);
		}

		return null;
	}

	public static WorkspaceEdit organizeImports(JavaClientConnection connection, CodeActionParams params) {
		String uri = params.getTextDocument().getUri();
		final ICompilationUnit unit = JDTUtils.resolveCompilationUnit(params.getTextDocument().getUri());
		if (unit == null) {
			return null;
		}

		TextEdit edit = organizeImports(unit, (importChoices, ranges) -> {
			Object commandResult = connection.executeClientCommand("java.action.organizeImports.chooseImport", importChoices, ranges, uri);
			return toModel(commandResult, ImportChoice[].class);
		});
		return SourceAssistProcessor.convertToWorkspaceEdit(unit, edit);
	}

	private static <T> T toModel(Object obj, Class<T> clazz) {
		try {
			if (obj == null) {
				return null;
			}

			if (clazz.isInstance(obj)) {
				return clazz.cast(obj);
			}

			final Gson GSON = new Gson();
			String json = GSON.toJson(obj);
			return GSON.fromJson(json, clazz);
		} catch (JsonSyntaxException ex) {
			JavaLanguageServerPlugin.logException("Failed to cast the value to " + clazz, ex);
		}

		return null;
	}

	public static class ImportChoice {
		public String qualifiedName;
		public String type;
		public String id;

		public ImportChoice() {
		}

		public ImportChoice(TypeNameMatch typeMatch) {
			qualifiedName = typeMatch.getFullyQualifiedName();
			id = typeMatch.getFullyQualifiedName() + "@" + typeMatch.hashCode();
		}
	}
}
