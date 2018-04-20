/*******************************************************************************
 * Copyright (c) 2007, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Originally copied from org.eclipse.jdt.internal.ui.text.correction.GetterSetterCorrectionSubProcessor
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.corrections.proposals;

import java.util.Collection;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.manipulation.CoreASTProvider;
import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.core.manipulation.util.BasicElementLabels;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.Messages;
import org.eclipse.jdt.ls.core.internal.corext.codemanipulation.GetterSetterUtil;
import org.eclipse.jdt.ls.core.internal.corrections.CorrectionMessages;
import org.eclipse.jdt.ls.core.internal.corrections.IInvocationContext;
import org.eclipse.jdt.ls.core.internal.corrections.IProblemLocation;

public class GetterSetterCorrectionSubProcessor {

	private static class ProposalParameter {
		public final boolean useSuper;
		public final ICompilationUnit compilationUnit;
		public final ASTRewrite astRewrite;
		public final Expression accessNode;
		public final Expression qualifier;
		public final IVariableBinding variableBinding;

		public ProposalParameter(boolean useSuper, ICompilationUnit compilationUnit, ASTRewrite rewrite, Expression accessNode, Expression qualifier, IVariableBinding variableBinding) {
			this.useSuper = useSuper;
			this.compilationUnit = compilationUnit;
			this.astRewrite = rewrite;
			this.accessNode = accessNode;
			this.qualifier = qualifier;
			this.variableBinding = variableBinding;
		}
	}

	public static void addGetterSetterProposal(IInvocationContext context, IProblemLocation location, Collection<CUCorrectionProposal> proposals, int relevance) {
		addGetterSetterProposal(context, location.getCoveringNode(context.getASTRoot()), proposals, relevance);
	}

	private static boolean addGetterSetterProposal(IInvocationContext context, ASTNode coveringNode, Collection<CUCorrectionProposal> proposals, int relevance) {
		if (!(coveringNode instanceof SimpleName)) {
			return false;
		}
		SimpleName sn = (SimpleName) coveringNode;

		IBinding binding = sn.resolveBinding();
		if (!(binding instanceof IVariableBinding)) {
			return false;
		}
		IVariableBinding variableBinding = (IVariableBinding) binding;
		if (!variableBinding.isField()) {
			return false;
		}

		if (proposals == null) {
			return true;
		}

		CUCorrectionProposal proposal = getProposal(context.getCompilationUnit(), sn, variableBinding, relevance);
		if (proposal != null) {
			proposals.add(proposal);
		}
		return true;
	}

	private static CUCorrectionProposal getProposal(ICompilationUnit cu, SimpleName sn, IVariableBinding variableBinding, int relevance) {
		Expression accessNode = sn;
		Expression qualifier = null;
		boolean useSuper = false;

		ASTNode parent = sn.getParent();
		switch (parent.getNodeType()) {
			case ASTNode.QUALIFIED_NAME:
				accessNode = (Expression) parent;
				qualifier = ((QualifiedName) parent).getQualifier();
				break;
			case ASTNode.SUPER_FIELD_ACCESS:
				accessNode = (Expression) parent;
				qualifier = ((SuperFieldAccess) parent).getQualifier();
				useSuper = true;
				break;
		}
		ASTRewrite rewrite = ASTRewrite.create(sn.getAST());
		ProposalParameter gspc = new ProposalParameter(useSuper, cu, rewrite, accessNode, qualifier, variableBinding);
		if (ASTResolving.isWriteAccess(sn)) {
			return addSetterProposal(gspc, relevance);
		} else {
			return addGetterProposal(gspc, relevance);
		}
	}

	/**
	 * Proposes a getter for this field.
	 *
	 * @param context
	 *            the proposal parameter
	 * @param relevance
	 *            relevance of this proposal
	 * @return the proposal if available or null
	 */
	private static CUCorrectionProposal addGetterProposal(ProposalParameter context, int relevance) {
		IMethodBinding method = findGetter(context);
		if (method != null) {
			Expression mi = createMethodInvocation(context, method, null);
			context.astRewrite.replace(context.accessNode, mi, null);

			String label = Messages.format(CorrectionMessages.GetterSetterCorrectionSubProcessor_replacewithgetter_description, BasicElementLabels.getJavaCodeString(ASTNodes.asString(context.accessNode)));
			ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(label, context.compilationUnit, context.astRewrite, relevance);
			return proposal;
		} else {
			IJavaElement element = context.variableBinding.getJavaElement();
			if (element instanceof IField) {
				IField field = (IField) element;
				CompilationUnit cu = CoreASTProvider.getInstance().getAST(field.getTypeRoot(), CoreASTProvider.WAIT_YES, null);
				try {
					if (isSelfEncapsulateAvailable(field)) {
						return new SelfEncapsulateFieldProposal(getDescription(field), field.getCompilationUnit(), cu.getRoot(), context.variableBinding, field, relevance);
					}
				} catch (JavaModelException e) {
					JavaLanguageServerPlugin.logException("Exception when adding getter proposal", e);
				}
			}
		}
		return null;
	}

	private static IMethodBinding findGetter(ProposalParameter context) {
		ITypeBinding returnType = context.variableBinding.getType();
		String getterName = GetterSetterUtil.getGetterName(context.variableBinding, context.compilationUnit.getJavaProject(), null, isBoolean(context));
		ITypeBinding declaringType = context.variableBinding.getDeclaringClass();
		if (declaringType == null) {
			return null;
		}
		IMethodBinding getter = Bindings.findMethodInHierarchy(declaringType, getterName, new ITypeBinding[0]);
		if (getter != null && getter.getReturnType().isAssignmentCompatible(returnType) && Modifier.isStatic(getter.getModifiers()) == Modifier.isStatic(context.variableBinding.getModifiers())) {
			return getter;
		}
		return null;
	}

	private static Expression createMethodInvocation(ProposalParameter context, IMethodBinding method, Expression argument) {
		AST ast = context.astRewrite.getAST();
		Expression qualifier = context.qualifier;
		if (context.useSuper) {
			SuperMethodInvocation invocation = ast.newSuperMethodInvocation();
			invocation.setName(ast.newSimpleName(method.getName()));
			if (qualifier != null) {
				invocation.setQualifier((Name) context.astRewrite.createCopyTarget(qualifier));
			}
			if (argument != null) {
				invocation.arguments().add(argument);
			}
			return invocation;
		} else {
			MethodInvocation invocation = ast.newMethodInvocation();
			invocation.setName(ast.newSimpleName(method.getName()));
			if (qualifier != null) {
				invocation.setExpression((Expression) context.astRewrite.createCopyTarget(qualifier));
			}
			if (argument != null) {
				invocation.arguments().add(argument);
			}
			return invocation;
		}
	}

	/**
	 * Proposes a setter for this field.
	 *
	 * @param context
	 *            the proposal parameter
	 * @param relevance
	 *            relevance of this proposal
	 * @return the proposal if available or null
	 */
	private static CUCorrectionProposal addSetterProposal(ProposalParameter context, int relevance) {
		boolean isBoolean = isBoolean(context);
		String setterName = GetterSetterUtil.getSetterName(context.variableBinding, context.compilationUnit.getJavaProject(), null, isBoolean);
		ITypeBinding declaringType = context.variableBinding.getDeclaringClass();
		if (declaringType == null) {
			return null;
		}

		IMethodBinding method = Bindings.findMethodInHierarchy(declaringType, setterName, new ITypeBinding[] { context.variableBinding.getType() });
		if (method != null && Bindings.isVoidType(method.getReturnType()) && (Modifier.isStatic(method.getModifiers()) == Modifier.isStatic(context.variableBinding.getModifiers()))) {
			Expression assignedValue = getAssignedValue(context);
			if (assignedValue == null) {
				return null; //we don't know how to handle those cases.
			}
			Expression mi = createMethodInvocation(context, method, assignedValue);
			context.astRewrite.replace(context.accessNode.getParent(), mi, null);

			String label = Messages.format(CorrectionMessages.GetterSetterCorrectionSubProcessor_replacewithsetter_description, BasicElementLabels.getJavaCodeString(ASTNodes.asString(context.accessNode)));
			ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(label, context.compilationUnit, context.astRewrite, relevance);
			return proposal;
		} else {
			IJavaElement element = context.variableBinding.getJavaElement();
			if (element instanceof IField) {
				IField field = (IField) element;
				CompilationUnit cu = CoreASTProvider.getInstance().getAST(field.getTypeRoot(), CoreASTProvider.WAIT_YES, null);
				try {
					if (isSelfEncapsulateAvailable(field)) {
						return new SelfEncapsulateFieldProposal(getDescription(field), field.getCompilationUnit(), cu.getRoot(), context.variableBinding, field, relevance);

					}
				} catch (JavaModelException e) {
					JavaLanguageServerPlugin.logException("Exception when adding setter proposal", e);
				}
			}
		}
		return null;
	}

	private static boolean isBoolean(ProposalParameter context) {
		AST ast = context.astRewrite.getAST();
		boolean isBoolean = ast.resolveWellKnownType("boolean") == context.variableBinding.getType(); //$NON-NLS-1$
		if (!isBoolean) {
			isBoolean = ast.resolveWellKnownType("java.lang.Boolean") == context.variableBinding.getType(); //$NON-NLS-1$
		}
		return isBoolean;
	}

	private static Expression getAssignedValue(ProposalParameter context) {
		ASTNode parent = context.accessNode.getParent();
		ASTRewrite astRewrite = context.astRewrite;
		IJavaProject javaProject = context.compilationUnit.getJavaProject();
		IMethodBinding getter = findGetter(context);
		Expression getterExpression = null;
		if (getter != null) {
			getterExpression = astRewrite.getAST().newSimpleName("placeholder"); //$NON-NLS-1$
		}
		ITypeBinding type = context.variableBinding.getType();
		boolean is50OrHigher = JavaModelUtil.is50OrHigher(javaProject);
		Expression result = GetterSetterUtil.getAssignedValue(parent, astRewrite, getterExpression, type, is50OrHigher);
		if (result != null && getterExpression != null && getterExpression.getParent() != null) {
			getterExpression.getParent().setStructuralProperty(getterExpression.getLocationInParent(), createMethodInvocation(context, getter, null));
		}
		return result;
	}

	private static String getDescription(IField field) {
		return Messages.format(CorrectionMessages.GetterSetterCorrectionSubProcessor_creategetterunsingencapsulatefield_description, BasicElementLabels.getJavaElementName(field.getElementName()));
	}

	private static boolean isSelfEncapsulateAvailable(IField field) throws JavaModelException {
		return isAvailable(field) && !JdtFlags.isEnum(field) && !field.getDeclaringType().isInterface();
	}

	private static boolean isAvailable(IJavaElement javaElement) throws JavaModelException {
		if (javaElement == null) {
			return false;
		}
		if (!javaElement.exists()) {
			return false;
		}
		if (javaElement.isReadOnly()) {
			return false;
		}
		// work around for https://bugs.eclipse.org/bugs/show_bug.cgi?id=48422
		// the Java project is now cheating regarding its children so we shouldn't
		// call isStructureKnown if the project isn't open.
		// see bug https://bugs.eclipse.org/bugs/show_bug.cgi?id=52474
		if (!(javaElement instanceof IJavaProject) && !(javaElement instanceof ILocalVariable) && !javaElement.isStructureKnown()) {
			return false;
		}
		if (javaElement instanceof IMember && ((IMember) javaElement).isBinary()) {
			return false;
		}
		return true;
	}
}
