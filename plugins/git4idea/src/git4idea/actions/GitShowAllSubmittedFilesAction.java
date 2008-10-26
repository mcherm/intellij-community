/*
 * Copyright 2000-2008 JetBrains s.r.o.
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
package git4idea.actions;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.config.GitVcsSettings;
import git4idea.i18n.GitBundle;
import git4idea.ui.GitUIUtil;

/**
 * Initial code for show submitted files action
 */
public class GitShowAllSubmittedFilesAction {
  /**
   * Show submitted files
   *
   * @param project  a project
   * @param settings a git settings
   * @param revision a file revision
   * @param file     file affected by the revision
   */
  public static void showSubmittedFiles(final Project project,
                                        GitVcsSettings settings,
                                        final VcsFileRevision revision,
                                        final VirtualFile file) {
    showSubmittedFiles(project, settings, revision.getRevisionNumber().asString(), file);
  }

  /**
   * Show submitted files
   *
   * @param project  a project
   * @param settings a git settings
   * @param revision a revision number
   * @param file     file affected by the revision
   */
  public static void showSubmittedFiles(final Project project, GitVcsSettings settings, final String revision, final VirtualFile file) {
    VirtualFile vcsRoot = GitUtil.getGitRoot(file);
    assert vcsRoot != null;
    GitCommand command = new GitCommand(project, settings, vcsRoot);
    try {
      final CommittedChangeList changeList = command.getRevisionChanges(revision);
      if (changeList != null) {
        AbstractVcsHelper.getInstance(project).showChangesListBrowser(changeList, getTitle(revision));
      }
    }
    catch (VcsException e) {
      GitUIUtil.showOperationError(project, e, "git show");
    }
  }


  /**
   * Get dialog title
   *
   * @param revNumber a revision number for the dialog
   * @return a dialog title
   */
  private static String getTitle(final String revNumber) {
    return GitBundle.message("paths.affected.title", revNumber);
  }
}
