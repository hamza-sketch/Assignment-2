package org.eclipse.jdt.ls.core.internal.corrections.proposals;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.ls.core.internal.StatusUtils;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

/**
 * A proposal for quick fixes and quick assists that works on an AST rewrite. Either a rewrite is
 * directly passed in the constructor or the method {@link #getRewrite()} is overridden to provide
 * the AST rewrite that is evaluated on the document when the proposal is applied.
 */
public class ASTRewriteCorrectionProposal extends CUCorrectionProposal {

	private ASTRewrite fRewrite;
	private ImportRewrite fImportRewrite;

	/**
	 * Constructs an AST rewrite correction proposal.
	 *
	 * @param name the display name of the proposal
	 * @param cu the compilation unit that is modified
	 * @param rewrite the AST rewrite that is invoked when the proposal is applied or
	 *            <code>null</code> if {@link #getRewrite()} is overridden
	 * @param relevance the relevance of this proposal
	 */
	public ASTRewriteCorrectionProposal(String name, ICompilationUnit cu, ASTRewrite rewrite, int relevance) {
		super(name, cu, relevance);
		fRewrite= rewrite;
	}

	/**
	 * Returns the import rewrite used for this compilation unit.
	 *
	 * @return the import rewrite or <code>null</code> if no import rewrite has been set
	 * @nooverride This method is not intended to be re-implemented or extended by clients.
	 */
	public ImportRewrite getImportRewrite() {
		return fImportRewrite;
	}

	/**
	 * Sets the import rewrite used for this compilation unit.
	 *
	 * @param rewrite the import rewrite
	 * @nooverride This method is not intended to be re-implemented or extended by clients.
	 */
	public void setImportRewrite(ImportRewrite rewrite) {
		fImportRewrite= rewrite;
	}

	/**
	 * Creates and sets the import rewrite used for this compilation unit.
	 *
	 * @param astRoot the AST for the current CU
	 * @return the created import rewrite
	 * @nooverride This method is not intended to be re-implemented or extended by clients.
	 */
	public ImportRewrite createImportRewrite(CompilationUnit astRoot) {
		fImportRewrite = ImportRewrite.create(astRoot, true); //TODO: Configure import rewrite
		return fImportRewrite;
	}


	@Override
	protected void addEdits(IDocument document, TextEdit editRoot) throws CoreException {
		super.addEdits(document, editRoot);
		ASTRewrite rewrite= getRewrite();
		if (rewrite != null) {
			try {
				TextEdit edit= rewrite.rewriteAST();
				editRoot.addChild(edit);
			} catch (IllegalArgumentException e) {
				throw new CoreException(StatusUtils.createError(IStatus.ERROR, e));
			}
		}
		if (fImportRewrite != null) {
			editRoot.addChild(fImportRewrite.rewriteImports(new NullProgressMonitor()));
		}
	}

	/**
	 * Returns the rewrite that has been passed in the constructor. Implementors can override this
	 * method to create the rewrite lazily. This method will only be called once.
	 *
	 * @return the rewrite to be used
	 * @throws CoreException when the rewrite could not be created
	 */
	protected ASTRewrite getRewrite() throws CoreException {
		if (fRewrite == null) {
			IStatus status = StatusUtils.createError(IStatus.ERROR, "Rewrite not initialized", null); //$NON-NLS-1$
			throw new CoreException(status);
		}
		return fRewrite;
	}
}
