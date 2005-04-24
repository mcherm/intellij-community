package com.intellij.refactoring.rename;

import com.intellij.j2ee.J2EERolesUtil;
import com.intellij.j2ee.ejb.EjbUsagesUtil;
import com.intellij.j2ee.ejb.EjbUtil;
import com.intellij.j2ee.ejb.role.EjbDeclMethodRole;
import com.intellij.j2ee.ejb.role.EjbMethodRole;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.HelpID;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.naming.AutomaticRenamer;
import com.intellij.refactoring.rename.naming.AutomaticVariableRenamer;
import com.intellij.refactoring.rename.naming.FormsRenamer;
import com.intellij.refactoring.rename.naming.InheritorRenamer;
import com.intellij.refactoring.ui.ConflictsDialog;
import com.intellij.refactoring.util.MoveRenameUsageInfo;
import com.intellij.refactoring.util.RefactoringHierarchyUtil;
import com.intellij.refactoring.util.RefactoringMessageUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.usageView.FindUsagesCommand;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.HashSet;

import java.util.*;

public class RenameProcessor extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.rename.RenameProcessor");

  LinkedHashMap<PsiElement, String> myAllRenames = new LinkedHashMap<PsiElement, String>();

  private PsiElement myPrimaryElement;
  private String myNewName = null;

  boolean mySearchInComments;
  private boolean mySearchInNonJavaFiles;
  private String myCommandName;
  private boolean myShouldRenameVariables;
  private boolean myShouldRenameInheritors;

  private boolean myShouldRenameForms;
  private UsageInfo[] myUsagesForNonCodeRenaming;
  private List<AutomaticRenamer> myRenamers = new ArrayList<AutomaticRenamer>();

  public RenameProcessor(Project project,
                         PsiElement element,
                         String newName,
                         boolean isSearchInComments,
                         boolean toSearchInNonJavaFiles) {
    super(project);
    myPrimaryElement = element;

    mySearchInComments = isSearchInComments;
    mySearchInNonJavaFiles = toSearchInNonJavaFiles;

    setNewName(newName);
  }

  public Set<PsiElement> getElements() {
    return Collections.unmodifiableSet(myAllRenames.keySet());
  }


  public void setShouldRenameVariables(boolean shouldRenameVariables) {
    myShouldRenameVariables = shouldRenameVariables;
  }

  public void setShouldRenameInheritors(boolean shouldRenameInheritors) {
    myShouldRenameInheritors = shouldRenameInheritors;
  }

  public void setShouldRenameForms(final boolean shouldRenameForms) {
    myShouldRenameForms = shouldRenameForms;
  }

  public RenameProcessor(Project project, PsiElement element) {
    super(project);
    myPrimaryElement = element;
  }

  public boolean isVariable() {
    return myPrimaryElement instanceof PsiVariable;
  }

  public void doRun() {
    String message = null;
    prepareRenaming();
    try {
      final Set entries = myAllRenames.entrySet();
      for (Iterator iterator = entries.iterator(); iterator.hasNext();) {
        Map.Entry<PsiElement, String> entry = (Map.Entry<PsiElement, String>)iterator.next();
        RenameUtil.checkRename(entry.getKey(), entry.getValue());
      }
    } catch (IncorrectOperationException e) {
      message = e.getMessage();
    }

    if (message != null) {
      RefactoringMessageUtil.showErrorMessage("Rename", message, getHelpID(), myProject);
      return;
    }

    super.doRun();
  }

  public void prepareRenaming() {
    if (myPrimaryElement instanceof PsiField) {
      prepareFieldRenaming((PsiField) myPrimaryElement, myNewName);
    } else if (myPrimaryElement instanceof PsiMethod) {
      prepareMethodRenaming((PsiMethod) myPrimaryElement, myNewName);
    } else if (myPrimaryElement instanceof PsiPackage) {
      preparePackageRenaming((PsiPackage) myPrimaryElement, myNewName);
    }
  }

  private void preparePackageRenaming(PsiPackage psiPackage, String newName) {
    final PsiDirectory[] directories = psiPackage.getDirectories();
    for (int i = 0; i < directories.length; i++) {
      PsiDirectory directory = directories[i];
      if (!directory.isSourceRoot()) {
        myAllRenames.put(directory, newName);
      }
    }
  }

  protected String getHelpID() {
    return HelpID.getRenameHelpID(myPrimaryElement);
  }

  protected boolean preprocessUsages(UsageInfo[][] usages) {
    String[] conflicts = RenameUtil.getConflictDescriptions(usages[0]);
    if (conflicts.length > 0) {
      ConflictsDialog conflictsDialog = new ConflictsDialog(conflicts, myProject);
      conflictsDialog.show();
      if (!conflictsDialog.isOK()) {
        return false;
      }
    }
    Set<UsageInfo> usagesSet = new HashSet<UsageInfo>(Arrays.asList(usages[0]));
    RenameUtil.removeConflictUsages(usagesSet);

    final List<UsageInfo> variableUsages = new ArrayList<UsageInfo>();
    if (!myRenamers.isEmpty()) {
      if (!findRenamedVariables(variableUsages)) return false;
    }

    if (!variableUsages.isEmpty()) {
      usagesSet.addAll(variableUsages);
      usages[0] = usagesSet.toArray(new UsageInfo[usagesSet.size()]);
    }

    prepareSuccessful();
    return true;
  }

  private boolean findRenamedVariables(final List<UsageInfo> variableUsages) {
    for (Iterator<AutomaticRenamer> iterator = myRenamers.iterator(); iterator.hasNext();) {
      final AutomaticRenamer automaticVariableRenamer = iterator.next();
      if (!automaticVariableRenamer.hasAnythingToRename()) continue;
      final AutomaticRenamingDialog dialog = new AutomaticRenamingDialog(myProject, automaticVariableRenamer);
      dialog.show();
      if (!dialog.isOK()) return false;
    }

    for (Iterator<AutomaticRenamer> iterator = myRenamers.iterator(); iterator.hasNext();) {
      final AutomaticRenamer renamer = iterator.next();
      final List<? extends PsiNamedElement> variables = renamer.getElements();
      for (Iterator<? extends PsiNamedElement> iterator1 = variables.iterator(); iterator1.hasNext();) {
        final PsiNamedElement variable = iterator1.next();
        addElement(variable, renamer.getNewName(variable));
      }
    }

    Runnable runnable = new Runnable() {
      public void run() {
        for (Iterator<AutomaticRenamer> iterator = myRenamers.iterator(); iterator.hasNext();) {
          final AutomaticRenamer renamer = iterator.next();
          renamer.findUsages(variableUsages, mySearchInComments, mySearchInNonJavaFiles);
        }
      }
    };

    final boolean isOK = ApplicationManager.getApplication().runProcessWithProgressSynchronously(
          runnable, "Searching for variables", true, myProject
        );
    return isOK;
  }

  public void addElement(PsiElement element, String newName) {
    myAllRenames.put(element, newName);
  }

  private void setNewName(String newName) {
    if (myPrimaryElement == null) {
      myCommandName = "Renaming something";
      return;
    }

    myNewName = newName;
    myAllRenames.put(myPrimaryElement, newName);
    myCommandName = "Renaming " + UsageViewUtil.getType(myPrimaryElement) + " " + UsageViewUtil.getDescriptiveName(myPrimaryElement) + " to " + newName;
  }

  protected void prepareFieldRenaming(PsiField field, String newName) {
    // search for getters/setters
    PsiClass aClass = field.getContainingClass();

    final CodeStyleManager manager = CodeStyleManager.getInstance(myProject);

    final String propertyName =
        manager.variableNameToPropertyName(field.getName(), VariableKind.FIELD);
    String newPropertyName = manager.variableNameToPropertyName(newName, VariableKind.FIELD);

    boolean isStatic = field.hasModifierProperty(PsiModifier.STATIC);
    PsiMethod getter = PropertyUtil.findPropertyGetter(aClass, propertyName, isStatic, false);
    PsiMethod setter = PropertyUtil.findPropertySetter(aClass, propertyName, isStatic, false);

    boolean shouldRenameSetterParameter = false;

    if (setter != null) {
      String parameterName = manager.propertyNameToVariableName(propertyName, VariableKind.PARAMETER);
      PsiParameter setterParameter = setter.getParameterList().getParameters()[0];
      shouldRenameSetterParameter = parameterName.equals(setterParameter.getName());
    }

    String newGetterName = "";
    String newSetterName = "";

    if (getter != null) {
      String getterId = getter.getName();
      newGetterName = PropertyUtil.suggestGetterName(newPropertyName, field.getType(), getterId);
      if (newGetterName.equals(getterId)) {
        getter = null;
        newGetterName = null;
      }
    }

    if (setter != null) {
      newSetterName = PropertyUtil.suggestSetterName(newPropertyName);
      final String newSetterParameterName = manager.propertyNameToVariableName(newPropertyName, VariableKind.PARAMETER);
      if (newSetterName.equals(setter.getName())) {
        setter = null;
        newSetterName = null;
        shouldRenameSetterParameter = false;
      } else if (newSetterParameterName.equals(setter.getParameterList().getParameters()[0].getName())) {
        shouldRenameSetterParameter = false;
      }
    }

    if (getter != null || setter != null) {
      if (askToRenameAccesors(getter, setter, newName)) {
        getter = null;
        setter = null;
        shouldRenameSetterParameter = false;
      }
    }

    if (getter != null) {
      addOverriddenAndImplemented(aClass, getter, newGetterName);
    }

    if (setter != null) {
      addOverriddenAndImplemented(aClass, setter, newSetterName);
    }

    if (shouldRenameSetterParameter) {
      PsiParameter parameter = setter.getParameterList().getParameters()[0];
      myAllRenames.put(parameter, manager.propertyNameToVariableName(newPropertyName, VariableKind.PARAMETER));
    }
  }

  protected boolean askToRenameAccesors(PsiMethod getter, PsiMethod setter, String newName) {
    if (ApplicationManager.getApplication().isUnitTestMode()) return false;
    String text = RefactoringMessageUtil.getGetterSetterMessage(newName, "Rename", getter, setter);
    return Messages.showYesNoDialog(myProject, text, "Rename", Messages.getQuestionIcon()) != 0;
  }

  private void addOverriddenAndImplemented(PsiClass aClass, PsiMethod methodPrototype, String newName) {
    final HashSet<PsiClass> superClasses = new HashSet<PsiClass>();
    RefactoringHierarchyUtil.getSuperClasses(aClass, superClasses, true);
    superClasses.add(aClass);

    for (Iterator<PsiClass> iterator = superClasses.iterator(); iterator.hasNext();) {
      PsiClass superClass = iterator.next();

      PsiMethod method = superClass.findMethodBySignature(methodPrototype, false);

      if (method != null) {
        myAllRenames.put(method, newName);
      }
    }
  }


  protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages, FindUsagesCommand refreshCommand) {
    return new RenameViewDescriptor(myPrimaryElement, myAllRenames, mySearchInComments, mySearchInNonJavaFiles, usages, refreshCommand);
  }

  protected UsageInfo[] findUsages() {
    myRenamers.clear();
    if (myPrimaryElement instanceof PsiDirectory) {
      final PsiPackage aPackage = ((PsiDirectory)myPrimaryElement).getPackage();
      final UsageInfo[] usages;
      if (aPackage != null) {
        usages = RenameUtil.findUsages(aPackage, myNewName, mySearchInComments, mySearchInNonJavaFiles, myAllRenames);
      }
      else {
        usages = RenameUtil.findUsages(myPrimaryElement, myNewName, mySearchInComments, mySearchInNonJavaFiles, myAllRenames);
      }
      return UsageViewUtil.removeDuplicatedUsages(usages);
    }

    ArrayList<UsageInfo> result = new ArrayList<UsageInfo>();

    final Set entries = myAllRenames.entrySet();
    for (Iterator iterator = entries.iterator(); iterator.hasNext();) {
      Map.Entry<PsiElement,String> entry = (Map.Entry<PsiElement,String>)iterator.next();
      PsiElement element = entry.getKey();
      final String newName = entry.getValue();
      final UsageInfo[] usages = RenameUtil.findUsages(element, newName, mySearchInComments, mySearchInNonJavaFiles, myAllRenames);
      result.addAll(Arrays.asList(usages));
      if (element instanceof PsiClass && myShouldRenameVariables) {
        myRenamers.add(new AutomaticVariableRenamer((PsiClass) element, newName, Arrays.asList(usages)));
      }
      if (element instanceof PsiClass && myShouldRenameInheritors) {
        if (((PsiClass)element).getName() != null) {
          myRenamers.add(new InheritorRenamer((PsiClass) element, newName));
        }
      }

      if (element instanceof PsiClass && myShouldRenameForms) {
        myRenamers.add(new FormsRenamer((PsiClass) element, newName));
      }
    }
    // add usages in ejb-jar.xml regardless of mySearchInNonJavaFiles setting
    // delete erroneous usages in ejb-jar.xml (e.g. belonging to another ejb)
    EjbUsagesUtil.adjustEjbUsages(myAllRenames, result);

    if (myPrimaryElement != null) {
      // add usages in ejb-jar.xml regardless of mySearchInNonJavaFiles setting
      // delete erroneous usages in ejb-jar.xml (e.g. belonging to another ejb)
      EjbUsagesUtil.adjustEjbUsages(myPrimaryElement, myNewName, result);
    }

    UsageInfo[] usageInfos = result.toArray(new UsageInfo[result.size()]);
    usageInfos = UsageViewUtil.removeDuplicatedUsages(usageInfos);
    return usageInfos;
  }

  protected void refreshElements(PsiElement[] elements) {
    LOG.assertTrue(elements.length > 0);
    if (myPrimaryElement != null) {
      myPrimaryElement = elements[0];
    }

    final Iterator<String> newNames = myAllRenames.values().iterator();
    LinkedHashMap<PsiElement, String> newAllRenames = new LinkedHashMap<PsiElement, String>();
    for (int i = 0; i < elements.length; i++) {
      newAllRenames.put(elements[i], newNames.next());
    }
    myAllRenames = newAllRenames;
  }

  protected boolean isPreviewUsages(UsageInfo[] usages) {
    if (super.isPreviewUsages(usages)) return true;
    if (UsageViewUtil.hasNonCodeUsages(usages)) {
      WindowManager.getInstance().getStatusBar(myProject).setInfo("Occurrences found in comments, strings and non-java files");
      return true;
    } else if (UsageViewUtil.hasReadOnlyUsages(usages)) {
      WindowManager.getInstance().getStatusBar(myProject).setInfo("Occurrences found in read-only files");
      return true;
    }
    return false;
  }

  protected void performRefactoring(UsageInfo[] usages) {
    List<Pair<String, RefactoringElementListener>> listenersForPackages = new ArrayList<Pair<String,RefactoringElementListener>>();

    final Set entries = myAllRenames.entrySet();
    for (Iterator iterator = entries.iterator(); iterator.hasNext();) {
      Map.Entry<PsiElement, String> entry = (Map.Entry<PsiElement, String>)iterator.next();
      PsiElement element = entry.getKey();
      String newName = entry.getValue();

      if (newName != null) {
        final RefactoringElementListener elementListener = getTransaction().getElementListener(element);
        RenameUtil.doRename(element, newName, extractUsagesForElement(element, usages), myProject, elementListener);
        if (element instanceof PsiPackage) {
          final PsiPackage psiPackage = (PsiPackage) element;
          final String newQualifiedName = RenameUtil.getQualifiedNameAfterRename(psiPackage.getQualifiedName(), newName);
          listenersForPackages.add(Pair.create(newQualifiedName, elementListener));
        }
      }
    }

    final PsiManager psiManager = PsiManager.getInstance(myProject);
    for (int i = 0; i < listenersForPackages.size(); i++) {
      Pair<String, RefactoringElementListener> pair = listenersForPackages.get(i);
      final PsiPackage aPackage = psiManager.findPackage(pair.getFirst());
      LOG.assertTrue(aPackage != null);
      pair.getSecond().elementRenamed(aPackage);
    }

    final UsageInfo[] usagesForNonCodeRenaming;
    usagesForNonCodeRenaming = usages;
    myUsagesForNonCodeRenaming = usagesForNonCodeRenaming;
  }

  protected void performPsiSpoilingRefactoring() {
    RefactoringUtil.renameNonCodeUsages(myProject, myUsagesForNonCodeRenaming);
  }

  protected String getCommandName() {
    return myCommandName;
  }

  private static UsageInfo[] extractUsagesForElement(PsiElement element, UsageInfo[] usages) {
    if (element instanceof PsiDirectory) {
      final PsiPackage aPackage = ((PsiDirectory)element).getPackage();
      if (aPackage != null) {
        element = aPackage;
      }
    }

    final ArrayList<UsageInfo> extractedUsages = new ArrayList<UsageInfo>(usages.length);
    for (int idx = 0; idx < usages.length; idx++) {
      UsageInfo usage = usages[idx];

      LOG.assertTrue(usage instanceof MoveRenameUsageInfo);

      MoveRenameUsageInfo usageInfo = (MoveRenameUsageInfo) usage;
      if (element.equals(usageInfo.referencedElement)) {
        extractedUsages.add(usageInfo);
      }
    }
    return extractedUsages.toArray(new UsageInfo[extractedUsages.size()]);
  }

  protected void prepareMethodRenaming(PsiMethod method, String newName) {
    final EjbMethodRole role = J2EERolesUtil.getEjbRole(method);
    if (role == null) return;

    if (role instanceof EjbDeclMethodRole) {
      final PsiMethod[] implementations = EjbUtil.findEjbImplementations(method);
      if (implementations.length == 0) return;

      final String[] names = EjbDeclMethodRole.suggestImplNames(newName, role.getType(), role.getEjb());
      for (int i = 0; i < implementations.length; i++) {
        if (i < names.length && names[i] != null) {
          myAllRenames.put(implementations[i], names[i]);
        }
      }
    }
  }

  protected void prepareTestRun() {
    prepareRenaming();
  }

  public Collection<String> getNewNames() {
    return myAllRenames.values();
  }

  public void setSearchInComments(boolean value) {
    mySearchInComments = value;
  }

  public void setSearchInNonJavaFiles(boolean searchInNonJavaFiles) {
    mySearchInNonJavaFiles = searchInNonJavaFiles;
  }

  public boolean isSearchInComments() {
    return mySearchInComments;
  }

  public boolean isSearchInNonJavaFiles() {
    return mySearchInNonJavaFiles;
  }


}
