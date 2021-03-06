package com.redhat.ceylon.compiler.typechecker.analyzer;

import static com.redhat.ceylon.compiler.typechecker.analyzer.AnalyzerUtil.NO_TYPE_ARGS;
import static com.redhat.ceylon.compiler.typechecker.analyzer.AnalyzerUtil.getPackageTypeDeclaration;
import static com.redhat.ceylon.compiler.typechecker.analyzer.AnalyzerUtil.getTypeDeclaration;
import static com.redhat.ceylon.compiler.typechecker.analyzer.AnalyzerUtil.isVeryAbstractClass;
import static com.redhat.ceylon.compiler.typechecker.analyzer.TypeVisitor.getTupleType;
import static com.redhat.ceylon.compiler.typechecker.parser.CeylonLexer.SPECIFY;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.buildAnnotations;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.formatPath;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.getAnnotation;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.getAnnotationArgument;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.getAnnotationArgumentCount;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.getAnnotationSequenceArgument;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.getNativeBackend;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.hasAnnotation;
import static com.redhat.ceylon.compiler.typechecker.tree.TreeUtil.name;
import static com.redhat.ceylon.compiler.typechecker.util.NativeUtil.checkNotJvm;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.getContainingClassOrInterface;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.getNativeHeader;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.getRealScope;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.getTypeArgumentMap;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.getVarianceMap;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.intersectionOfSupertypes;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.isConstructor;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.isImplemented;
import static com.redhat.ceylon.model.typechecker.model.ModelUtil.isNativeHeader;
import static com.redhat.ceylon.model.typechecker.model.Module.LANGUAGE_MODULE_NAME;
import static java.lang.Integer.parseInt;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.redhat.ceylon.common.Backend;
import com.redhat.ceylon.common.Backends;
import com.redhat.ceylon.compiler.typechecker.tree.CustomTree;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;
import com.redhat.ceylon.model.typechecker.model.Class;
import com.redhat.ceylon.model.typechecker.model.ClassAlias;
import com.redhat.ceylon.model.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.model.typechecker.model.ConditionScope;
import com.redhat.ceylon.model.typechecker.model.Constructor;
import com.redhat.ceylon.model.typechecker.model.ControlBlock;
import com.redhat.ceylon.model.typechecker.model.Declaration;
import com.redhat.ceylon.model.typechecker.model.Element;
import com.redhat.ceylon.model.typechecker.model.Function;
import com.redhat.ceylon.model.typechecker.model.FunctionOrValue;
import com.redhat.ceylon.model.typechecker.model.Generic;
import com.redhat.ceylon.model.typechecker.model.ImportList;
import com.redhat.ceylon.model.typechecker.model.Interface;
import com.redhat.ceylon.model.typechecker.model.InterfaceAlias;
import com.redhat.ceylon.model.typechecker.model.IntersectionType;
import com.redhat.ceylon.model.typechecker.model.LazyType;
import com.redhat.ceylon.model.typechecker.model.ModelUtil;
import com.redhat.ceylon.model.typechecker.model.NamedArgumentList;
import com.redhat.ceylon.model.typechecker.model.Package;
import com.redhat.ceylon.model.typechecker.model.Parameter;
import com.redhat.ceylon.model.typechecker.model.ParameterList;
import com.redhat.ceylon.model.typechecker.model.Scope;
import com.redhat.ceylon.model.typechecker.model.Setter;
import com.redhat.ceylon.model.typechecker.model.SiteVariance;
import com.redhat.ceylon.model.typechecker.model.Specification;
import com.redhat.ceylon.model.typechecker.model.Type;
import com.redhat.ceylon.model.typechecker.model.TypeAlias;
import com.redhat.ceylon.model.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.model.typechecker.model.TypeParameter;
import com.redhat.ceylon.model.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.model.typechecker.model.UnionType;
import com.redhat.ceylon.model.typechecker.model.Unit;
import com.redhat.ceylon.model.typechecker.model.UnknownType;
import com.redhat.ceylon.model.typechecker.model.Value;

/**
 * First phase of type analysis.
 * Scan a compilation unit searching for declarations, and 
 * builds up the model objects. At this point, all we know 
 * is the name of the declaration and what kind of 
 * declaration it is. The model objects do not contain type 
 * information.
 * 
 * @author Gavin King
 *
 */
public abstract class DeclarationVisitor extends Visitor {
    
    private static final ClassOrInterface[] NO_CLASSES = new ClassOrInterface[0];
    private static final FunctionOrValue[] NO_FUNCTIONS_OR_VALUES = new FunctionOrValue[0];
    private static final Constructor[] NO_CONSTRUCTORS = new Constructor[0];
    
    private final Package pkg;
    private Scope scope;
    private Unit unit;
    private ParameterList parameterList;
    private Declaration declaration;
    private boolean dynamic;
    
    public DeclarationVisitor(Unit unit) {
        this.unit = unit;
        this.pkg = unit.getPackage();
        scope = pkg;
    }

    public Unit getCompilationUnit() {
        return unit;
    }
    
    private Scope enterScope(Scope innerScope) {
        Scope outerScope = scope;
        scope = innerScope;
        return outerScope;
    }

    private void exitScope(Scope outerScope) {
        scope = outerScope;
    }
    
    private Declaration beginDeclaration(Declaration innerDec) {
        Declaration outerDec = declaration;
        declaration = innerDec;
        return outerDec;
    }

    private void endDeclaration(Declaration outerDec) {
        declaration = outerDec;
    }
    
    private void visitDeclaration(Tree.Declaration that, 
            Declaration model) {
        visitDeclaration(that,  model, true);
    }
    
    private void visitDeclaration(Tree.Declaration that, 
            Declaration model, boolean checkDupe) {
        visitElement(that, model);
        
        handleDeclarationAnnotations(that, model);
        
        setVisibleScope(model);
        
        checkFormalMember(that, model);
        
        Tree.Identifier id = that.getIdentifier();
        if (setModelName(that, model, id)) {
            if (checkDupe) {
                checkForNativeAnnotation(that, 
                        model, scope);
                checkForDuplicateDeclaration(that, 
                        model, scope);
            }
        }
        //that.setDeclarationModel(model);
        unit.addDeclaration(model);
        Scope sc = getContainer(that);
        sc.addMember(model);
    }

    private void visitArgument(Tree.NamedArgument that, 
            Declaration model) {
        Tree.Identifier id = that.getIdentifier();
        setModelName(that, model, id);
        visitElement(that, model);
        //that.setDeclarationModel(model);
        unit.addDeclaration(model);
        setVisibleScope(model);
    }

    private void visitArgument(Tree.Term that, 
            Declaration model) {
        visitElement(that, model);
        //that.setDeclarationModel(model);
        unit.addDeclaration(model);
        setVisibleScope(model);
    }

    private static boolean setModelName(Node that, 
            Declaration model,
            Tree.Identifier id) {
        if (id==null || id.isMissingToken()) {
            if (that instanceof Tree.Constructor) {
                return true;                
            }
            else {
                that.addError("missing declaration or argument name");
                return false;
            }
        }
        else {
            model.setName(id.getText());
            return true;
            //TODO: check for dupe arg name
        }
    }
    
    private void checkForNativeAnnotation(
            Tree.Declaration that, 
            Declaration model, Scope scope) {
        Unit unit = model.getUnit();
        if (model.isNative()) {
            Backends mbackends = model.getNativeBackends();
            boolean isHeader = model.isNativeHeader();
            String name = model.getName();
            boolean canBeNative = canBeNative(that);
            if (canBeNative) {
                Backends moduleBackends = 
                        unit.getPackage()
                            .getModule()
                            .getNativeBackends();
                Backends backends = 
                        model.getScope()
                            .getScopedBackends();
                if (!isHeader &&
                        !moduleBackends.none() &&
                        !mbackends.supports(moduleBackends)) {
                    that.addError("native backend name on declaration conflicts with module descriptor: '\"" +
                            mbackends.names() + "\"' is not '\"" +
                            moduleBackends.names() + "\"' for '" +
                            name + "'");
                } else if (!isHeader &&
                        !backends.none() &&
                        !backends.supports(mbackends)) {
                    that.addError("native backend for declaration conflicts with its scope: native implementation '" +
                            name + "' for '\"" + 
                            mbackends.names() +
                            "\"' occurs in a scope which only supports '\"" + 
                            backends.names() +
                            "\"'");
                }
                if (isHeader && existImplementations(model)) {
                    that.addError("native header must be declared before its implementations: the native header '" +
                            name + "' is declared after an implementation");
                }
                if (model instanceof Interface
                        && ((Interface)model).isAlias()) {
                    that.addError("interface alias may not be marked native: '" +
                            name + "' (add a body if a native interface was intended)");
                }
                model.setNativeBackends(mbackends);
                Declaration member = getNativeHeader(model);
                
                if (member == null || 
                        member.isNativeImplementation()) {
                    // Abstraction-less native implementation, check
                    // it's not shared
                    if (!isHeader && 
                            mustHaveHeader(model)) {
                        that.addError("shared native implementation must have a header: '" +
                                model.getName() + "' has no native header");
                    }
                }
                if (member == null) {
                    if (model.isNativeHeader()) {
                        handleNativeHeader(model, name);
                        if (that instanceof Tree.ObjectDefinition) {
                            Tree.ObjectDefinition od = 
                                    (Tree.ObjectDefinition) 
                                        that;
                            handleNativeHeader(
                                    od.getAnonymousClass(),
                                    name);
                        } else if (that instanceof Tree.Constructor) {
                            Tree.Constructor c = 
                                    (Tree.Constructor) 
                                        that;
                            handleNativeHeader(
                                    c.getConstructor(),
                                    name);
                        }
                    }
                    else {
                        member = model.getContainer()
                                    .getDirectMemberForBackend(
                                        model.getName(), 
                                        mbackends);
                        if (member != null && member != model) {
                            that.addError("duplicate native implementation: the implementation '" + 
                                        name + "' for '\"" + 
                                        mbackends.names() +
                                        "\"' is not unique");
                            unit.getDuplicateDeclarations()
                                .add(member);
                        }
                    }
                }
                else {
                    if (member.isNative()) {
                        List<Declaration> overloads = 
                                member.getOverloads();
                        if (isHeader && 
                                member.isNativeHeader()) {
                            that.addError("duplicate native header: the header for '" + 
                                    name + "' is not unique");
                            unit.getDuplicateDeclarations()
                                .add(member);
                        }
                        else {
                            Declaration overload = 
                                    findOverloadForBackend(
                                            mbackends, model, 
                                            overloads);
                            if (overload != null) {
                                that.addError("duplicate native implementation: the implementation '" + 
                                        name + "' for '\"" + 
                                        mbackends.names() +
                                        "\"' is not unique");
                                unit.getDuplicateDeclarations()
                                    .add(overload);
                            }
                        } 
                        if (isAllowedToChangeModel(member) && 
                                !hasModelInOverloads(model, 
                                        overloads)) {
                            overloads.add(model);
                            if (that instanceof Tree.ObjectDefinition) {
                                Tree.ObjectDefinition od = 
                                        (Tree.ObjectDefinition) 
                                            that;
                                Declaration objImplCls = 
                                        od.getAnonymousClass();
                                Value value = (Value) member;
                                Class objHdrCls = 
                                        (Class) 
                                            value.getType()
                                                .getDeclaration();
                                objHdrCls.getOverloads()
                                    .add(objImplCls);
                            }
                            else if (that instanceof Tree.Constructor) {
                                Tree.Constructor c = 
                                        (Tree.Constructor) 
                                            that;
                                Declaration cd = 
                                        c.getConstructor();
                                FunctionOrValue fov = 
                                        (FunctionOrValue) member;
                                Constructor hdr = 
                                        (Constructor) 
                                            fov.getType()
                                                .getDeclaration();
                                hdr.getOverloads()
                                    .add(cd);
                            }
                        }
                    }
                    else {
                        if (isHeader) {
                            that.addError("native header for non-native declaration: '" + 
                                    name + "' is not declared native");
                        }
                        else {
                            that.addError("native implementation for non-native header: '" + 
                                    name + "' is not declared native");
                        }
                    }
                }
            }
            else if (!(model instanceof Setter) && !isHeader) {
                if (!canBeNative) {
                    that.addError("native declaration is not a class, constructor, method, attribute or object: '" + 
                            name + "' may not be annotated 'native'");
                }
            }
        }
    }
    
    private boolean existImplementations(Declaration hdr) {
        List<Declaration> decls = ModelUtil.lookupOverloadedByName(
                hdr.getScope().getMembers(), hdr.getName());
        for (Declaration decl : decls) {
            if (decl.isNativeImplementation()) {
                return true;
            }
        }
        return false;
    }
    
    private static boolean canBeNative(Tree.Declaration that) {
        return that instanceof Tree.ClassOrInterface
            || that instanceof Tree.Constructor
            || that instanceof Tree.Enumerated
            || that instanceof Tree.AnyMethod
            || that instanceof Tree.AnyAttribute
            || that instanceof Tree.ObjectDefinition;
    }
    
    protected static boolean canBeNative(Declaration member) {
        return member instanceof Function || 
                member instanceof Value || 
                member instanceof ClassOrInterface;
    }

    private static boolean mustHaveHeader(Declaration model) {
        if (model.isShared()) {
            if (model.isToplevel()) {
                return true;
            }
            else if (model.isMember()) {
                Declaration container = 
                        (Declaration)
                            model.getContainer();
                return !container.isNative() ||
                        container.isNativeHeader();
            }
        }
        return false;
    }
    
    private void handleNativeHeader(Declaration model, String name) {
        // Deal with implementations from the ModelLoader
        ArrayList<FunctionOrValue> loadedFunctionsOrValues = null;
        ArrayList<ClassOrInterface> loadedClasses = null;
        ArrayList<Constructor> loadedConstructors = null;
        for (Backend backendToSearch: Backend.getRegisteredBackends()) {
            Declaration overloadFromModelLoader = 
                    model.getContainer()
                        .getDirectMemberForBackend(name, 
                                backendToSearch.asSet());
            if (overloadFromModelLoader 
                    instanceof FunctionOrValue) {
                if (loadedFunctionsOrValues == null) {
                    loadedFunctionsOrValues = 
                            new ArrayList<FunctionOrValue>();
                }
                FunctionOrValue fov = 
                        (FunctionOrValue) 
                            overloadFromModelLoader;
                loadedFunctionsOrValues.add(fov);
            }
            else if (overloadFromModelLoader instanceof ClassOrInterface) {
                if (loadedClasses == null) {
                    loadedClasses = new ArrayList<ClassOrInterface>();
                }
                ClassOrInterface c = (ClassOrInterface) overloadFromModelLoader;
                loadedClasses.add(c);
            }
            else if (overloadFromModelLoader instanceof Constructor) {
                if (loadedConstructors == null) {
                    loadedConstructors = new ArrayList<Constructor>();
                }
                Constructor c = (Constructor) overloadFromModelLoader;
                loadedConstructors.add(c);
            }
        }
        // Initialize the header's overloads
        if (model instanceof FunctionOrValue) {
            FunctionOrValue m = (FunctionOrValue) model;
            if (loadedFunctionsOrValues!=null) {
                m.initOverloads(
                        loadedFunctionsOrValues.toArray(
                                NO_FUNCTIONS_OR_VALUES));
            }
            else {
                m.initOverloads();
            }
        }
        else if (model instanceof ClassOrInterface) {
            ClassOrInterface c = (ClassOrInterface) model;
            if (loadedClasses != null) {
                c.initOverloads(
                        loadedClasses.toArray(NO_CLASSES));

            }
            else {
                c.initOverloads();
            }
        }
        else if (model instanceof Constructor) {
            Constructor c = (Constructor) model;
            if (loadedConstructors != null) {
                c.initOverloads(
                        loadedConstructors.toArray(NO_CONSTRUCTORS));

            }
            else {
                c.initOverloads();
            }
        }
    }
    
    private Declaration findOverloadForBackend(Backends backends, 
            Declaration declaration, 
            List<Declaration> overloads) {
        if (overloads!=null) {
            for (Declaration overload: overloads) {
                if (backends.supports(overload.getNativeBackends()) && 
                    !shouldIgnoreOverload(overload, declaration)) {
                    return overload;
                }
            }
        }
        return null;
    }

    private boolean hasModelInOverloads(
            Declaration declaration, 
            List<Declaration> overloads) {
        if (overloads!=null) {
            for (Declaration overload: overloads) {
                if (overload == declaration) {
                    return true;
                }
            }
        }
        return false;
    }
    
    protected abstract boolean shouldIgnoreOverload(
            Declaration overload,
            Declaration declaration);

    protected abstract boolean isAllowedToChangeModel(
            Declaration declaration);

    private static void checkForDuplicateDeclaration(
            Tree.Declaration that, 
            Declaration model, 
            Scope scope) {
        String name = model.getName();
        Unit unit = model.getUnit();
        if (name!=null) {
            if (model instanceof Setter) {
                Setter setter = (Setter) model;
                checkGetterForSetter(that, setter, scope);
            }
            else {
                // this isn't the correct scope for declaration
                // which follow an assertion, since it misses
                // condition scopes, so use the argument scope
//                Scope scope = model.getContainer();
                boolean isControl;
                do {
                    Declaration member = 
                            scope.getDirectMember(name, 
                                    null, false);
                    if (member!=null && member!=model) {
                        boolean dup = false;
                        boolean possibleOverloadedMethod = 
                                model.isActual() &&
                                member.isActual() &&
                                member instanceof Function && 
                                model instanceof Function &&
                                !(that instanceof Tree.Constructor ||
                                  that instanceof Tree.Enumerated) &&
                                scope instanceof ClassOrInterface &&
                                !member.isNative();
                        if (possibleOverloadedMethod) {
                            // anticipate that it might be
                            // an overloaded method 
                            // overriding a method inherited 
                            // from a Java superclass - then
                            // further checking happens in
                            // RefinementVisitor
                            initOverload(model, member, 
                                    scope, unit);
                        }
                        else if (canBeNative(member) && 
                                 canBeNative(model) &&
                                 model.isNative()) {
                            // just to make sure no error 
                            // gets reported
                        }
                        else {
                            dup = true;
                            that.addError("duplicate declaration: the name '" + 
                                    name + "' is not unique in this scope");
                        }
                        if (dup) {
                            unit.getDuplicateDeclarations()
                                .add(member);
                        }
                    }
                    isControl = scope instanceof ControlBlock;
                    scope = scope.getContainer();
                }
                while (isControl);
            }
        }
    }

    private static void checkGetterForSetter(Tree.Declaration that,
            Setter setter, Scope scope) {
        //a setter must have a matching getter
        Tree.AnnotationList al = that.getAnnotationList();
        String name = setter.getName();
        Unit unit = setter.getUnit();
        Declaration member = 
                setter.getContainer()
                    .getDirectMemberForBackend(name, 
                        getNativeBackend(al, unit));
        if (member==null) {
            that.addError("setter with no matching getter: there is no getter named '" + 
                    name + "' already declared in this scope (declare a matching getter earlier in this scope)");
        }
        else if (!(member instanceof Value)) {
            that.addError("setter name does not resolve to matching getter: '" + 
                    name + "' is not a getter");
        }
        else if (member.isNative() && !setter.isNative()) {
            setter.setGetter((Value)member);
            that.addError("setter must be marked native: the getter '" +
                    name + "' is annotated 'native'");
        }
        else if (!member.isNative() && setter.isNative()) {
            setter.setGetter((Value)member);
            that.addError("setter may not be marked native: the getter '" +
                    name + "' is not annotated 'native'");
        }
        else if (!((Value) member).isTransient() && 
                !isNativeHeader(member)) {
            that.addError("matching value is a reference or is forward-declared: '" + 
                    name + "' is not a getter");
        }
        else {
            Value getter = (Value) member;
            setter.setGetter(getter);
            if (getter.isVariable()) {
                that.addError("duplicate setter for getter: '" + 
                        name + "' already has a setter");
            }
            else {
                getter.setSetter(setter);
            }
            setter.setNativeBackends(getter.getNativeBackends());
            setter.setStatic(getter.isStatic());
        }
    }

    private static void initOverload(
            Declaration model, Declaration member,
            Scope scope, Unit unit) {
        //even though Ceylon does not 
        //officially support overloading,
        //we actually do let you overload
        //a method that is refining an
        //overloaded method inherited from
        //a Java superclass
        Function abstraction;
        Function method = 
                (Function) member;
        Function newMethod = 
                (Function) model;
        newMethod.setOverloaded(true);
        if (!method.isAbstraction()) {
            //create the "abstraction" 
            //for the overloaded method
            method.setOverloaded(true);
            abstraction = new Function();
            abstraction.setAbstraction(true);
            abstraction.setType(
                    new UnknownType(unit)
                        .getType());
            abstraction.setName(model.getName());
            abstraction.setShared(true);
            abstraction.setActual(true);
            abstraction.setContainer(scope);
            abstraction.setScope(scope);
            abstraction.setUnit(unit);
            abstraction.initOverloads(
                    method, newMethod);
            scope.addMember(abstraction);
        }
        else {
            abstraction = method;
            abstraction.getOverloads()
                .add(model);
        }
    }

    private void visitElement(Node that, Element model) {
        model.setUnit(unit);
        model.setScope(scope);
        model.setContainer(getContainer(that));
    }

    /**
     * Get the containing scope, skipping any condition
     * scopes in the case of a regular declaration. 
     * 
     * @see com.redhat.ceylon.model.typechecker.model.ConditionScope
     */
    private Scope getContainer(Node that) {
        if (that instanceof Tree.Declaration &&
                !(that instanceof Tree.Parameter) &&
                !(that instanceof Tree.Variable)) {
            Scope s = scope;
            while (s instanceof ConditionScope) {
                s = s.getScope();
            }
            return s;
        } 
        else {
            return scope;
        }
    }
    
    @Override
    public void visitAny(Node that) {
        that.setScope(scope);
        that.setUnit(unit);
        super.visitAny(that);
    }
    
    @Override
    public void visit(Tree.DynamicStatement that) {
        boolean od = dynamic;
        dynamic = true;
        super.visit(that);
        dynamic = od;
        checkNotJvm(that, 
                "dynamic is not supported on the JVM");
    }

    @Override
    public void visit(Tree.Dynamic that) {
        super.visit(that);
        checkNotJvm(that, 
                "dynamic is not supported on the JVM");
    }
    
    @Override
    public void visit(Tree.DynamicModifier that) {
        super.visit(that);
        checkNotJvm(that, 
                "dynamic is not supported on the JVM");
    }
    
    @Override
    public void visit(Tree.CompilationUnit that) {
//        unit.setSupportedBackends(that.getUnit().getSupportedBackends());
        pkg.removeUnit(unit);
        pkg.addUnit(unit);
        super.visit(that);
        Node firstNonImportNode = null;
        int index = -1;
        for (Node d: that.getDeclarations()) {
            firstNonImportNode = d;
            index = d.getToken().getTokenIndex();
            break;
        }
        for (Tree.ModuleDescriptor md: 
                that.getModuleDescriptors()) {
            if (index<0 || 
                    md.getToken().getTokenIndex()<index) {
                firstNonImportNode = md;
                index = md.getToken().getTokenIndex();
            }
            break;
        }
        for (Tree.PackageDescriptor pd: 
                that.getPackageDescriptors()) {
            if (index<0 || 
                    pd.getToken().getTokenIndex()<index) {
                firstNonImportNode = pd;
                index = pd.getToken().getTokenIndex();
            }
            break;
        }
        if (firstNonImportNode!=null) {
            for (Tree.Import im: 
                    that.getImportList().getImports()) {
                if (im.getEndIndex() > 
                        firstNonImportNode.getStartIndex()) {
                    im.addError("import statement must occur before any declaration or descriptor");
                }
            }
        }
        boolean first=true;
        for (Tree.ModuleDescriptor md: 
                that.getModuleDescriptors()) {
            if (!first) {
                md.addError("there may be only one module descriptor for a module");
            }
            first=false;
        }
        first=true;
        for (Tree.PackageDescriptor pd: 
                that.getPackageDescriptors()) {
            if (!first) {
                pd.addError("there may be only one package descriptor for a module");
            }
            first=false;
        }
    }
    
    @Override
    public void visit(Tree.ImportMemberOrTypeList that) {
        ImportList il = new ImportList();
        unit.getImportLists().add(il);
        that.setImportList(il);
        il.setContainer(scope);
        il.setUnit(unit);
        Scope o = enterScope(il);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.TypeParameterList that) {
        super.visit(that);
        Generic g = (Generic) declaration;
        g.setTypeParameters(getTypeParameters(that));
    }    
    
    private void defaultExtendedToBasic(Class c) {
        //default supertype for classes
        c.setExtendedType(new LazyType(unit) {
            @Override
            public Map<TypeParameter, Type> 
            initTypeArguments() {
                return emptyMap();
            }
            @Override
            public TypeDeclaration initDeclaration() {
                return unit.getBasicDeclaration();
            }
        });
    }
    
    private void defaultExtendedToObject(Interface c) {
        //default supertype for interfaces
        c.setExtendedType(new LazyType(unit) {
            @Override
            public Map<TypeParameter, Type> 
            initTypeArguments() {
                return emptyMap();
            }
            @Override
            public TypeDeclaration initDeclaration() {
                return unit.getObjectDeclaration();
            }
        });
    }

    private void defaultExtendedToAnything(TypeParameter c) {
        //default supertype for interfaces
        c.setExtendedType(new LazyType(unit) {
            @Override
            public Map<TypeParameter, Type> 
            initTypeArguments() {
                return emptyMap();
            }
            @Override
            public TypeDeclaration initDeclaration() {
                return unit.getAnythingDeclaration();
            }
        });
    }
    
    @Override
    public void visit(Tree.ClassDefinition that) {
        Class c = new Class();
        if (!isVeryAbstractClass(that, unit)) {
            defaultExtendedToBasic(c);
        }
        that.setDeclarationModel(c);
        super.visit(that);
        if (that.getParameterList()==null) {
            if (c.isClassOrInterfaceMember() &&
                    (c.isFormal() || 
                     c.isDefault() ||
                     c.isActual())) {
                that.addError("member class declared 'formal', 'default', or 'actual' must have a parameter list");
            }
            Tree.AnnotationList al = 
                    that.getAnnotationList();
            if (hasAnnotation(al, "sealed", unit)) {
                that.addError("class without parameter list may not be annotated 'sealed'", 
                        1800);
            }
        }
        if (c.isSealed() && c.isFormal() && 
                c.isClassOrInterfaceMember()) {
            ClassOrInterface container = 
                    (ClassOrInterface) c.getContainer();
            if (!container.isSealed()) {
                that.addError("sealed formal member class does not belong to a sealed type", 
                        1801);
            }
        }
        if (c.isNativeImplementation()) {
            addMissingHeaderMembers(c);
        }
    }
    
    @Override
    public void visit(Tree.ClassDeclaration that) {
        Class c = new ClassAlias();
        that.setDeclarationModel(c);
        super.visit(that);
        if (that.getParameterList()==null) {
            that.addError("class alias must have a parameter list");
        }
    }
    
    @Override
    public void visit(Tree.AnyClass that) {
        Class c = that.getDeclarationModel();
        visitDeclaration(that, c);
        Scope o = enterScope(c);
        super.visit(that);
        exitScope(o);
        Tree.ParameterList pl = 
                that.getParameterList();
        if (pl!=null) {
            pl.getModel().setFirst(true);
            c.addParameterList(pl.getModel());
        }
        //TODO: is this still necessary??
        if (c.isClassOrInterfaceMember() && 
                c.getContainer() 
                    instanceof TypedDeclaration) {
            that.addUnsupportedError("nested classes of inner classes are not yet supported");
        }
        Tree.Identifier identifier = that.getIdentifier();
        if (c.isAbstract() && c.isFinal()) {
            that.addError("class may not be both 'abstract' and 'final': '" + 
                    name(identifier) + "'");
        }
        if (c.isFormal() && c.isFinal()) {
            that.addError("class may not be both 'formal' and 'final': '" + 
                    name(identifier) + "'");
        }
        if (hasAnnotation(that.getAnnotationList(), "service", that.getUnit())) {
            if (!c.getTypeParameters().isEmpty()) {
                that.addError("service class may not be generic");
            }
        }
    }
    
    @Override
    public void visit(Tree.Constructor that) {
        Constructor c = new Constructor();
        that.setConstructor(c);
        if (scope instanceof Class) {
            Class clazz = (Class) scope;
            if (that.getIdentifier()==null && 
                    clazz.getDefaultConstructor()!=null) {
                that.addError("duplicate default constructor: '" +
                        clazz.getName() + 
                        "' may have at most one default constructor");
                unit.getDuplicateDeclarations().add(c);
            }
        }
        visitDeclaration(that, c, false);
        Type at;
        Scope realScope = getRealScope(scope);
        if (realScope instanceof Class) {
            Class clazz = (Class) realScope;
            Type ot = clazz.getType();
            c.setExtendedType(ot);
            at = c.appliedType(ot, NO_TYPE_ARGS);
            clazz.setConstructors(true);
            if (clazz.isAnonymous()) {
                that.addError("anonymous class may not have constructor: '" + 
                        clazz.getName() + "' is an anonymous class");
            }
        }
        else {
            at = null;
            that.addError("constructor declaration must occur directly in the body of a class");
        }
        Function f = new Function();
        f.setType(at);
        that.setDeclarationModel(f);
        visitDeclaration(that, f);
        Scope o = enterScope(c);
        super.visit(that);
        exitScope(o);
        f.setImplemented(that.getBlock() != null);
        if (that.getParameterList()==null) {
            that.addError("missing parameter list in constructor declaration: '" + 
                    name(that.getIdentifier()) + 
                    "' must have a parameter list", 
                    1000);
        }
        else {
            ParameterList model = 
                    that.getParameterList()
                        .getModel();
            model.setFirst(true);
            if (model!=null) {
                c.addParameterList(model);
                //TODO: fix this
                f.addParameterList(model);
            }
        }
        if (that.getIdentifier()==null) {
            //default constructor
            if (!c.isShared()) {
                that.addError("default constructor must be annotated 'shared'", 
                        705);
            }
            if (c.isAbstract()) {
                that.addError("default constructor may not be annotated 'abstract'", 
                        1601);
            }
//        if (scope instanceof Class) {
//            Class clazz = (Class) scope;
//            //constructor of sealed class implicitly inherits sealed
//            if (clazz.isSealed()) {
//                c.setSealed(true);
//            }
//        }
        }
        if (c.isAbstract() && c.isShared()) {
            that.addError("abstract constructor may not be annotated 'shared'", 
                    1610);
        }
    }

    @Override
    public void visit(Tree.Enumerated that) {
        Constructor e = new Constructor();
        that.setEnumerated(e);
        visitDeclaration(that, e, false);
        Type at;
        if (scope instanceof Class) {
            Class clazz = (Class) scope;
            Type ot = clazz.getType();
            e.setExtendedType(ot);
            at = e.appliedType(ot, NO_TYPE_ARGS);
            clazz.setEnumerated(true);
            if (clazz.isAnonymous()) {
                that.addError("anonymous class may not have a value constructor: '" + 
                        clazz.getName() + "' is an anonymous class");
            }
            else if (clazz.isAbstract()) {
                that.addError("abstract class may not have a value constructor: '" + 
                        clazz.getName() + "' is abstract");
            }
            else if (!clazz.getTypeParameters().isEmpty()) {
                that.addError("generic class may not have a value constructor: '" + 
                        clazz.getName() + "' is generic");
            }
            else if (scope.getContainer() instanceof Interface) {
                that.addError("class nested inside an interface may not have a value constructor: '" +
                        clazz.getName() + "' belongs to an interface");
            }
        }
        else {
            at = null;
            that.addError("value constructor declaration must occur directly in the body of a class");
        }
        Value v = new Value();
        v.setType(at);
        that.setDeclarationModel(v);
        visitDeclaration(that, v);
        Scope o = enterScope(e);
        super.visit(that);
        exitScope(o);
        v.setImplemented(that.getBlock() != null);
    }

    @Override
    public void visit(Tree.InterfaceDefinition that) {
        Interface i = new Interface();
        i.setDynamic(that.getDynamic());
        defaultExtendedToObject(i);
        that.setDeclarationModel(i);
        super.visit(that);
        if (i.isNativeImplementation()) {
            addMissingHeaderMembers(i);
        }
        if (i.isDynamic()) {
            i.makeMembersDynamic();
        }
        // Required by IDE to be omitted: https://github.com/ceylon/ceylon-compiler/issues/2326
//        if (that.getDynamic()) {
//            checkDynamic(that);
//        }
    }
    
    // Given a native implementation this adds all the members from
    // its native header (if it has one) that it doesn't already have
    // as members itself
    private void addMissingHeaderMembers(ClassOrInterface coi) {
        Declaration hdr = getNativeHeader(coi);
        if (hdr != null) {
            HashSet<String> names = new HashSet<String>();
            for (Declaration im : coi.getMembers()) {
                if (!im.isMember()) {
                    continue;
                }
                names.add(im.getQualifiedNameString());
            }
            for (Declaration hm : hdr.getMembers()) {
                if (!hm.isMember()) {
                    continue;
                }
                if (!names.contains(hm.getQualifiedNameString())
                        && (coi instanceof Interface || isImplemented(hm))) {
                    coi.addMember(hm);
                }
            }
        }
    }

    @Override
    public void visit(Tree.InterfaceDeclaration that) {
        InterfaceAlias i = new InterfaceAlias();
        that.setDeclarationModel(i);
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.AnyInterface that) {
        Interface i = that.getDeclarationModel();
        that.setDeclarationModel(i);
        visitDeclaration(that, i);
        Scope o = enterScope(i);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.TypeAliasDeclaration that) {
        TypeAlias a = new TypeAlias();
        that.setDeclarationModel(a);
        visitDeclaration(that, a);
        Scope o = enterScope(a);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.TypeParameterDeclaration that) {
        Tree.TypeVariance tv = that.getTypeVariance();
        final TypeParameter p = new TypeParameter();
        defaultExtendedToAnything(p);
        p.setDeclaration(declaration);
        if (tv!=null) {
            String v = tv.getText();
            p.setCovariant("out".equals(v));
            p.setContravariant("in".equals(v));
        }
        that.setDeclarationModel(p);
        visitDeclaration(that, p);
        
        super.visit(that);
        
        Tree.TypeSpecifier ts = that.getTypeSpecifier();
        if (ts!=null) {
            p.setDefaulted(true);
            Tree.StaticType type = ts.getType();
            if (type!=null) {
                Type dta = type.getTypeModel();
                p.setDefaultTypeArgument(dta);
            }
        }
    }
    
    @Override
    public void visit(Tree.AnyMethod that) {
        Function m = new Function();
        that.setDeclarationModel(m);
        visitDeclaration(that, m);
        Scope o = enterScope(m);
        super.visit(that);
        exitScope(o);
        setParameterLists(m, that.getParameterLists(), that);
        Tree.Type type = that.getType();
        m.setDeclaredVoid(type instanceof Tree.VoidModifier);
        if (type instanceof Tree.ValueModifier) {
            type.addError("functions may not be declared using the keyword value");
        }
        if (type instanceof Tree.DynamicModifier) {
            m.setDynamicallyTyped(true);
        }
    }
    
    private static void setParameterLists(Function m, 
            List<Tree.ParameterList> paramLists, 
            Node that) {
        if (m!=null) {
            for (Tree.ParameterList pl: paramLists) {
                m.addParameterList(pl.getModel());
            }
        }
        if (paramLists.isEmpty()) {
            that.addError("missing parameter list in function declaration", 
                    1000);
        }
        else {
            paramLists.get(0).getModel().setFirst(true);
            for (int i=0; i<paramLists.size()-1; i++) {
                Tree.ParameterList pl = paramLists.get(i);
                for (Tree.Parameter p: pl.getParameters()) {
                    if (p instanceof Tree.InitializerParameter) {
                        p.addError("split parameter declaration only allowed in last parameter list");
                    }
                }
            }
        }
    }
    
    @Override
    public void visit(Tree.AnyAttribute that) {
        super.visit(that);
        Tree.Type type = that.getType();
        if (type instanceof Tree.FunctionModifier) {
            type.addError("values may not be declared using the keyword function");
        }
        if (type instanceof Tree.DynamicModifier) {
            that.getDeclarationModel()
                .setDynamicallyTyped(true);
        }
    }

    @Override
    public void visit(Tree.MethodArgument that) {
        Function m = new Function();
        m.setImplemented(that.getBlock() != null);
        that.setDeclarationModel(m);
        visitArgument(that, m);
        Scope o = enterScope(m);
        super.visit(that);
        exitScope(o);
        setParameterLists(m, that.getParameterLists(), that);
        Tree.Type type = that.getType();
        m.setDeclaredVoid(type instanceof Tree.VoidModifier);
    }
    
    private int fid=0;

    @Override
    public void visit(Tree.FunctionArgument that) {
        Tree.Type type = that.getType();
        final Function f = new Function();
        f.setName("anonymous#"+fid++);
        f.setAnonymous(true);
        if (type.getToken()!=null) {
            f.setDeclaredVoid(type instanceof Tree.VoidModifier);
        }
        else {
            Tree.Block block = that.getBlock();
            if (block!=null) {
                f.setDeclaredVoid(true);
                new Visitor() {
                    @Override
                    public void visit(Tree.Declaration that) {}
                    @Override
                    public void visit(Tree.TypedArgument that) {}
                    @Override
                    public void visit(Tree.ObjectExpression that) {}
                    @Override
                    public void visit(Tree.FunctionArgument that) {}
                    @Override
                    public void visit(Tree.Return that) {
                        if (that.getExpression()!=null) {
                            f.setDeclaredVoid(false);
                        }
                        super.visit(that);
                    }
                }.visit(block);
            }
        }
        that.setDeclarationModel(f);
        visitArgument(that, f);
        Scope o = enterScope(f);
        Declaration d = beginDeclaration(f);
        super.visit(that);
        endDeclaration(d);
        exitScope(o);
        setParameterLists(f, that.getParameterLists(), that);
    }
    
    @Override
    public void visit(Tree.ObjectDefinition that) {
        Class c = new Class();
        defaultExtendedToBasic(c);
        c.setAnonymous(true);
        that.setAnonymousClass(c);
        visitDeclaration(that, c, false);
        Value v = new Value();
        that.setDeclarationModel(v);
        visitDeclaration(that, v);
        Type t = c.getType();
        that.getType().setTypeModel(t);
        v.setType(t);
        v.setStatic(c.isStatic());
        if (c.isStatic()) {
            Scope container = v.getContainer();
            if (container instanceof ClassOrInterface) {
                ClassOrInterface ci = 
                        (ClassOrInterface) 
                            container;
                if (!ci.getTypeParameters().isEmpty()) {
                    that.addError("anonymous class belonging to generic type may not be annotated 'static'");
                }
            }
        }
        Scope o = enterScope(c);
        super.visit(that);
        exitScope(o);
        if (c.isInterfaceMember()) {
            that.addError("object declaration may not occur directly in interface body");
        }
        if (c.isNativeImplementation()) {
            addMissingHeaderMembers(c);
        }
    }

    @Override
    public void visit(Tree.ObjectArgument that) {
        Class c = new Class();
        defaultExtendedToBasic(c);
        c.setAnonymous(true);
        that.setAnonymousClass(c);
        visitArgument(that, c);
        Value v = new Value();
        that.setDeclarationModel(v);
        visitArgument(that, v);
        Type t = c.getType();
        that.getType().setTypeModel(t);
        v.setType(t);
        Scope o = enterScope(c);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.ObjectExpression that) {
        Class c = new Class();
        c.setName("anonymous#"+fid++);
        c.setNamed(false);
        defaultExtendedToBasic(c);
        c.setAnonymous(true);
        that.setAnonymousClass(c);
        visitArgument(that, c);
        Scope o = enterScope(c);
        super.visit(that);
        exitScope(o);
    }
    
    private boolean mustHaveExplicitType(FunctionOrValue fov) {
        return fov.isShared() && !fov.isActual();
    }
    
    @Override
    public void visit(Tree.AttributeDeclaration that) {
        Value v = new Value();
        that.setDeclarationModel(v);
        Tree.SpecifierOrInitializerExpression sie = 
                that.getSpecifierOrInitializerExpression();
        boolean lazy = 
                sie instanceof Tree.LazySpecifierExpression;
        v.setTransient(lazy);
        v.setImplemented(sie != null);
        visitDeclaration(that, v);
        if (!lazy && v.isVariable() && v.isStatic()) {
            Scope container = v.getContainer();
            if (container instanceof ClassOrInterface) {
                ClassOrInterface ci = 
                        (ClassOrInterface) 
                            container;
                if (!ci.getTypeParameters().isEmpty()) {
                    that.addError("attribute of generic type may not be annotated both 'variable' and 'static'");
                }
            }
        }
        Scope o = null;
//        if (lazy) 
            o = enterScope(v);
        super.visit(that);
//        if (lazy) 
            exitScope(o);
        if (v.isInterfaceMember() && 
                !v.isFormal() && !v.isNative()) {
            if (sie==null) {
                that.addError("interface attribute must be annotated 'formal'", 
                        1400);
            }
        }
        if (v.isLate()) {
            if (v.isFormal()) {
                that.addError("formal attribute may not be annotated 'late'");
            }
            else if (!v.isClassOrInterfaceMember() && 
                    !v.isToplevel()) {
                that.addError("block-local value may not be annotated 'late'");
            }
        }
        if (v.isFormal() && sie!=null) {
            that.addError("formal attributes may not have a value", 
                    1102);
        }
        Tree.Type type = that.getType();
        if (type instanceof Tree.ValueModifier) {
            if (v.isToplevel()) {
                if (sie==null) {
                    type.addError("toplevel value must explicitly specify a type");
                }
                else {
                    type.addError("toplevel value must explicitly specify a type", 
                            200);
                }
            }
            else if (mustHaveExplicitType(v)) {
                type.addError("shared value must explicitly specify a type", 
                        200);
            }
        }
    }

    @Override
    public void visit(Tree.MethodDeclaration that) {
        super.visit(that);
        Tree.SpecifierExpression sie = 
                that.getSpecifierExpression();
        Function m = that.getDeclarationModel();
        m.setImplemented(sie != null);
        if (m.isFormal() && sie!=null) {
            that.addError("formal method may not have a specification", 
                    1102);
        }
        Tree.Type type = that.getType();
        if (type instanceof Tree.FunctionModifier) {
            if (m.isToplevel()) {
                if (sie==null) {
                    type.addError("toplevel function must explicitly specify a return type");
                }
                else {
                    type.addError("toplevel function must explicitly specify a return type", 
                            200);
                }
            }
            else if (mustHaveExplicitType(m)) {
                type.addError("shared function must explicitly specify a return type", 
                        200);
            }
        }
    }
            
    @Override
    public void visit(Tree.MethodDefinition that) {
        super.visit(that);
        Function m = that.getDeclarationModel();
        m.setImplemented(that.getBlock() != null);
        Tree.Type type = that.getType();
        if (type instanceof Tree.FunctionModifier) {
            if (m.isToplevel()) {
                type.addError("toplevel function must explicitly specify a return type", 
                        200);
            }
            else if (mustHaveExplicitType(m) && !dynamic) {
                type.addError("shared function must explicitly specify a return type", 
                        200);
            }
        }
    }
            
    @Override
    public void visit(Tree.AttributeGetterDefinition that) {
        Value g = new Value();
        g.setTransient(true);
        g.setImplemented(that.getBlock() != null);
        that.setDeclarationModel(g);
        visitDeclaration(that, g);
        Scope o = enterScope(g);
        super.visit(that);
        exitScope(o);
        Tree.Type type = that.getType();
        if (type instanceof Tree.ValueModifier) {
            if (g.isToplevel()) {
                type.addError("toplevel getter must explicitly specify a type", 
                        200);
            }
            else if (mustHaveExplicitType(g) && !dynamic) {
                type.addError("shared getter must explicitly specify a type", 
                        200);
            }
        }
    }
    
    @Override
    public void visit(Tree.AttributeArgument that) {
        Value g = new Value();
        g.setTransient(true);
        g.setImplemented(that.getBlock() != null);
        that.setDeclarationModel(g);
        visitArgument(that, g);
        Scope o = enterScope(g);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.AttributeSetterDefinition that) {
        Setter s = new Setter();
        s.setImplemented(that.getBlock() != null);
        that.setDeclarationModel(s);
        visitDeclaration(that, s);
        Scope o = enterScope(s);
        Parameter p = new Parameter();
        p.setHidden(true);
        Value v = new Value();
        v.setInitializerParameter(p);
        p.setModel(v);
        v.setName(s.getName());
        p.setName(s.getName());
        p.setDeclaration(s);
        visitElement(that, v);
        unit.addDeclaration(v);
        Scope sc = getContainer(that);
        sc.addMember(v);
        s.setParameter(p);
        super.visit(that);
        exitScope(o);
        
        if (that.getSpecifierExpression()==null &&
                that.getBlock()==null &&
                !isNativeHeader(s) &&
                !isNativeHeader(s.getGetter())) {
            that.addError("setter declaration must have a body or => specifier");
        }
    }

    @Override
    public void visit(Tree.MissingDeclaration that) {
        Value value = new Value();
        that.setDeclarationModel(value);
        visitDeclaration(that, value);
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.InitializerParameter that) {
        Parameter p = new Parameter();
        p.setDeclaration(declaration);
        p.setDefaulted(that.getSpecifierExpression()!=null);
        p.setHidden(true);
        p.setName(that.getIdentifier().getText());
        that.setParameterModel(p);
//        visitDeclaration(that, p);
        super.visit(that);
        parameterList.getParameters().add(p);
    }

    @Override
    public void visit(Tree.Parameter that) {
        super.visit(that);
        Tree.SpecifierOrInitializerExpression sie = null;
        if (that instanceof Tree.ParameterDeclaration) {
            Tree.ParameterDeclaration pd = 
                    (Tree.ParameterDeclaration) that;
            Tree.TypedDeclaration td = 
                    pd.getTypedDeclaration();
            if (td instanceof Tree.AttributeDeclaration) {
                Tree.AttributeDeclaration ad = 
                        (Tree.AttributeDeclaration) td;
                sie = ad.getSpecifierOrInitializerExpression();
            }
            else if (td instanceof Tree.MethodDeclaration) {
                Tree.MethodDeclaration md = 
                        (Tree.MethodDeclaration) td;
                sie = md.getSpecifierExpression();
            }
        }
        else if (that instanceof Tree.InitializerParameter) {
            Tree.InitializerParameter ip = 
                    (Tree.InitializerParameter) that;
            sie = ip.getSpecifierExpression();
        }
        if (sie!=null) {
            if (scope instanceof ClassAlias) {
                sie.addUnsupportedError("defaulted parameters are not yet supported for class aliases");
            }
            new Visitor() {
                public void visit(Tree.AssignmentOp that) {
                    that.addError("assignment may not occur in default argument expression");
                }
                @Override
                public void visit(Tree.PostfixOperatorExpression that) {
                    that.addError("postfix increment or decrement may not occur in default argument expression");
                }
                @Override
                public void visit(Tree.PrefixOperatorExpression that) {
                    that.addError("prefix increment or decrement may not occur in default argument expression");
                }
            }.visit(sie);
        }
    }
    
    private static Tree.SpecifierOrInitializerExpression 
    getSpecifier(Tree.ValueParameterDeclaration that) {
        Tree.AttributeDeclaration ad = 
                (Tree.AttributeDeclaration) 
                    that.getTypedDeclaration();
        return ad.getSpecifierOrInitializerExpression();
    }

    private static Tree.SpecifierExpression 
    getSpecifier(Tree.FunctionalParameterDeclaration that) {
        Tree.MethodDeclaration md = 
                (Tree.MethodDeclaration) 
                    that.getTypedDeclaration();
        return md.getSpecifierExpression();
    }
    
    @Override
    public void visit(Tree.ValueParameterDeclaration that) {
        Parameter p = new Parameter();
        p.setDeclaration(declaration);
        p.setDefaulted(getSpecifier(that)!=null);
        Tree.TypedDeclaration td = 
                that.getTypedDeclaration();
        Tree.Type type = td.getType();
        p.setSequenced(type instanceof Tree.SequencedType);
        that.setParameterModel(p);
        super.visit(that);
        Value v = (Value) td.getDeclarationModel();
        p.setName(v.getName());
        p.setModel(v);
        v.setInitializerParameter(p);
        parameterList.getParameters().add(p);
        if (p.isSequenced() && p.isDefaulted()) {
            getSpecifier(that).addError("variadic parameter may not specify default argument");
        }
        if (p.isSequenced()) {
            Tree.SequencedType st = 
                    (Tree.SequencedType) type;
            if (st.getAtLeastOne()) {
                p.setAtLeastOne(true);
            }
        }
        if (v.isFormal()) {
            that.addError("parameter may not be annotated 'formal'", 
                    1312);
        }
//        if (v.isVariable()) {
//            that.addError("parameter may not be annotated 'variable'");
//        }
    }

    @Override
    public void visit(Tree.FunctionalParameterDeclaration that) {
        Parameter p = new Parameter();
        p.setDeclaration(declaration);
        p.setDefaulted(getSpecifier(that)!=null);
        Tree.TypedDeclaration td = 
                that.getTypedDeclaration();
        Tree.Type type = td.getType();
        p.setDeclaredAnything(type instanceof Tree.VoidModifier);
        that.setParameterModel(p);
        super.visit(that);
        Function m = (Function) td.getDeclarationModel();
        p.setModel(m);
        p.setName(m.getName());
        m.setInitializerParameter(p);
        parameterList.getParameters().add(p);
        if (type instanceof Tree.SequencedType) {
            type.addError("functional parameter type may not be variadic");
        }
        if (m.isFormal()) {
            that.addError("parameter may not be annotated 'formal'", 
                    1312);
        }
    }

    @Override
    public void visit(Tree.ParameterList that) {
        ParameterList pl = parameterList;
        parameterList = new ParameterList();
        that.setModel(parameterList);
        super.visit(that);
        parameterList = pl;        
    }
    
    private int id=0;
    
    @Override
    public void visit(Tree.ControlClause that) {
        ControlBlock cb = new ControlBlock();
        cb.setLet(that instanceof Tree.LetClause);
        cb.setId(id++);
        that.setControlBlock(cb);
        visitElement(that, cb);
        Scope o = enterScope(cb);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.SwitchStatement that) {
        ControlBlock cb = new ControlBlock();
        cb.setId(id++);
        that.setControlBlock(cb);
        visitElement(that, cb);
        Scope o = enterScope(cb);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.SwitchExpression that) {
        ControlBlock cb = new ControlBlock();
        cb.setId(id++);
        visitElement(that, cb);
        Scope o = enterScope(cb);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.Condition that) {
        ConditionScope cb = new ConditionScope();
        cb.setId(id++);
        that.setScope(cb);
        visitElement(that, cb);
        enterScope(cb);
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.ExistsOrNonemptyCondition that) {
        super.visit(that);
        String op = 
                that instanceof Tree.ExistsCondition ? 
                        "exists" : "nonempty";
        Tree.Expression be = that.getBrokenExpression();
        if (be!=null) {
            be.addError("incorrect syntax: " + op + 
                    " conditions do not apply to arbitrary expressions, try using postfix '" + 
                    op + "' operator", 
                    3100);
        }
        else if (that.getVariable()==null) {
            that.addError("missing variable or immutable value reference: " + 
                    op + " condition requires an operand");
        }
    }
    
    @Override
    public void visit(Tree.Body that) {
        addGuardedVariables(that, 
                that instanceof Tree.ClassBody);
        
        int oid=id;
        id=0;
        super.visit(that);
        id=oid;
    }

    private static void addGuardedVariables(Tree.Body that, 
            boolean isInitializer) {
        List<Tree.Statement> originals = 
                that.getStatements();
        int originalSize = originals.size();
        List<Tree.Statement> result = 
                new ArrayList<Tree.Statement>(originalSize);
        for (int i=0; i<originalSize; i++) {
            Tree.Statement st = originals.get(i);
            result.add(st);
            if (i<originalSize-1 &&
                    st instanceof Tree.IfStatement) {
                Tree.IfStatement ifst =
                        (Tree.IfStatement) st;
                Tree.IfClause ifcl = ifst.getIfClause();
                if (ifcl!=null) {
                    Tree.Block block = ifcl.getBlock();
                    if (block!=null) {
                        List<Tree.Statement> statements = 
                                block.getStatements();
                        if (!statements.isEmpty()) {
                            int size = statements.size();
                            Tree.Statement last = 
                                    statements.get(size-1);
                            if (definitelyReturns(last, 
                                    isInitializer, false)) {
                                Tree.ConditionList cl = 
                                        ifcl.getConditionList();
                                Tree.Variable v = 
                                       guardedVariable(cl,
                                               true);
                                if (v!=null) {
                                    result.add(v);
                                }                                
                            }
                        }
                    }
                }
                Tree.ElseClause elcl = ifst.getElseClause();
                if (elcl!=null) {
                    Tree.Block block = elcl.getBlock();
                    if (block!=null) {
                        List<Tree.Statement> statements = 
                                block.getStatements();
                        if (!statements.isEmpty()) {
                            int size = statements.size();
                            Tree.Statement last = 
                                    statements.get(size-1);
                            if (definitelyReturns(last, 
                                    isInitializer, false)) {
                                Tree.ConditionList cl = 
                                        ifcl.getConditionList();
                                Tree.Variable v = 
                                       guardedVariable(cl, 
                                               false);
                                if (v!=null) {
                                    result.add(v);
                                }                                
                            }
                        }
                    }
                }
            }
        }
        originals.clear();
        originals.addAll(result);
    }

    public static boolean definitelyReturns(Tree.Statement last, 
            boolean isInitializer, boolean withinInnerLoop) {
        if (last instanceof Tree.Throw ||
            !isInitializer && last instanceof Tree.Return ||
            !withinInnerLoop && (last instanceof Tree.Break 
                              || last instanceof Tree.Continue)) {
            return true;
        }
        else if (last instanceof Tree.IfStatement) {
            Tree.IfStatement is = (Tree.IfStatement) last;
            Tree.IfClause ic = is.getIfClause();
            Tree.ElseClause ec = is.getElseClause();
            if (ic!=null && ec!=null) {
                Tree.Block icb = ic.getBlock();
                Tree.Block ecb = ec.getBlock();
                if (icb!=null && ecb!=null) {
                    List<Tree.Statement> ists = 
                            icb.getStatements();
                    List<Tree.Statement> ests = 
                            ecb.getStatements();
                    if (!ists.isEmpty() && !ests.isEmpty()) {
                        Tree.Statement ilast =
                                ists.get(ists.size()-1);
                        Tree.Statement elast =
                                ests.get(ests.size()-1);
                        return definitelyReturns(ilast,
                                        isInitializer,
                                        withinInnerLoop) &&
                                definitelyReturns(elast,
                                        isInitializer,
                                        withinInnerLoop);
                    }
                }
            }
            return false;
        }
        else if (last instanceof Tree.SwitchStatement) {
            Tree.SwitchStatement ss = 
                    (Tree.SwitchStatement) last;
            Tree.SwitchCaseList scl = ss.getSwitchCaseList();
            if (scl!=null) {
                for (Tree.CaseClause cc: scl.getCaseClauses()) {
                    Tree.Block cb = cc.getBlock();
                    if (cb == null) {
                        return false;
                    }
                    else {
                        List<Tree.Statement> csts = 
                                cb.getStatements();
                        if (csts.isEmpty()) {
                            return false;
                        }
                        else {
                            Tree.Statement clast =
                                    csts.get(csts.size()-1);
                            if (!definitelyReturns(clast, 
                                    isInitializer,
                                    withinInnerLoop)) {
                                return false;
                            }
                        }
                    }
                }
                Tree.ElseClause ec = scl.getElseClause();
                if (ec!=null) {
                    Tree.Block ecb = ec.getBlock();
                    if (ecb == null) {
                        return false;
                    }
                    else {
                        List<Tree.Statement> ests = 
                                ecb.getStatements();
                        if (ests.isEmpty()) {
                            return false;
                        }
                        else {
                            Tree.Statement elast =
                                    ests.get(ests.size()-1);
                            if (!definitelyReturns(elast, 
                                    isInitializer,
                                    withinInnerLoop)) {
                                return false;
                            }
                        }
                    }
                }
                return true;
            }
            return false;
        }
        //TODO: do something with try/catch/finally
        //      detect try and every catch definitely
        //      return, or that finally definitely returns
        else if (last instanceof Tree.ForStatement) {
            Tree.ForStatement fs = (Tree.ForStatement) last;
            Tree.ElseClause ec = fs.getElseClause();
            Tree.ForClause fc = fs.getForClause();
            if (ec!=null && fc!=null) {
                Tree.Block ecb = ec.getBlock();
                Tree.Block fcb = fc.getBlock();
                if (ecb!=null && ecb!=null) {
                    List<Tree.Statement> fsts = 
                            fcb.getStatements();
                    List<Tree.Statement> ests = 
                            ecb.getStatements();
                    //TODO: detect the case where the body
                    //      of the for loop never breaks
                    //TODO: detect the case where we are
                    //      iterating something known to be
                    //      nonempty
                    if (!ests.isEmpty() && !fsts.isEmpty()) {
                        Tree.Statement flast =
                                fsts.get(fsts.size()-1);
                        Tree.Statement elast =
                                ests.get(ests.size()-1);
                        return definitelyReturns(flast, //|| definitelyDoesNotBreak(flast)
                                        isInitializer,
                                        true) &&
                                definitelyReturns(elast, 
                                        isInitializer,
                                        true);
                    }
                }
            }
            return false;
        }
        else {
            return false;
        }
    }
    
    private static Tree.Variable guardedVariable(
            Tree.ConditionList cl, boolean reversed) {
        if (cl!=null) {
            List<Tree.Condition> conditions = 
                    cl.getConditions();
            if (conditions.size()==1) {
                Tree.Condition c = conditions.get(0);
                Tree.Identifier id = null;
                Tree.Type t = null;
                if (c instanceof Tree.ExistsOrNonemptyCondition) {
                    Tree.ExistsOrNonemptyCondition enc = 
                            (Tree.ExistsOrNonemptyCondition) c;
                    Tree.Statement s = enc.getVariable();
                    if (s instanceof Tree.Variable) {
                        Tree.Variable v = 
                                (Tree.Variable) s;
                        if (v!=null) {
                            t = v.getType();
                            id = v.getIdentifier();
                        }
                    }
                }
                else if (c instanceof Tree.IsCondition) {
                    Tree.IsCondition ic = (Tree.IsCondition) c;
                    Tree.Variable v = 
                            (Tree.Variable) 
                                ic.getVariable();
                    if (v!=null) {
                        t = v.getType();
                        id = v.getIdentifier();
                    }
                }
                if (id!=null && t instanceof Tree.SyntheticVariable) { 
                    CustomTree.GuardedVariable ev = 
                            new CustomTree.GuardedVariable(null);
                    ev.setReversed(reversed);
                    ev.setConditionList(cl);
                    ev.setType(new Tree.SyntheticVariable(null));
                    Tree.SpecifierExpression ese = 
                            new Tree.SpecifierExpression(null);
                    Tree.Expression ee = 
                            new Tree.Expression(null);
                    Tree.BaseMemberExpression ebme = 
                            new Tree.BaseMemberExpression(null);
                    ebme.setTypeArguments(
                            new Tree.InferredTypeArguments(null));
                    ee.setTerm(ebme);
                    ese.setExpression(ee);
                    ev.setSpecifierExpression(ese);
                    ev.setIdentifier(id);
                    ebme.setIdentifier(id);
                    return ev;
                }
            }
        }
        return null;
    }
    
    @Override
    public void visit(Tree.NamedArgumentList that) {
        NamedArgumentList nal = 
                new NamedArgumentList();
        nal.setId(id++);
        for (Tree.NamedArgument na: 
                that.getNamedArguments()) {
            Tree.Identifier id = na.getIdentifier();
            if (id!=null) {
                nal.getArgumentNames()
                    .add(id.getText());
            }
        }
        that.setNamedArgumentList(nal);
        visitElement(that, nal);
        Scope o = enterScope(nal);
        super.visit(that);
        exitScope(o);
    }
    
    @Override
    public void visit(Tree.Variable that) {
        if (that instanceof CustomTree.GuardedVariable) {
            ConditionScope cb = new ConditionScope();
            cb.setId(id++);
            that.setScope(cb);
            visitElement(that, cb);
            enterScope(cb);
        }
        
        Tree.SpecifierExpression se = 
                that.getSpecifierExpression();
        if (se!=null) {
            Scope s = scope;
            if (scope instanceof ControlBlock) {
                ControlBlock block = (ControlBlock) scope;
                if (!block.isLet()) {
                    scope = scope.getContainer();
                }
            }
            se.visit(this);
            scope = s;
        }
        
        Tree.Type type = that.getType();
        Value v = new Value();
        that.setDeclarationModel(v);
        visitDeclaration(that, v, 
                !(type instanceof Tree.SyntheticVariable));
        setVisibleScope(v);
        
        if (type!=null) {
            type.visit(this);
        }
        Tree.Identifier identifier = 
                that.getIdentifier();
        if (identifier!=null) {
            identifier.visit(this);
        }
        
        //TODO: scope should be the variable, not the 
        //      containing control structure:
        Tree.AnnotationList al = 
                that.getAnnotationList();
        if (al!=null) {
            al.visit(this);
        }
        List<Tree.ParameterList> pls = 
                that.getParameterLists();
        for (Tree.ParameterList pl: pls) {
            pl.visit(this);
        }
        
        //TODO: parameters of callable variables are allowed 
        //      in for loops, according to the language spec
        /*Declaration od = beginDeclaration(v);
        Scope os = enterScope(v);
        if (that.getAnnotationList()!=null) {
            that.getAnnotationList().visit(this);
        }
        for (Tree.ParameterList pl: that.getParameterLists()) {
            pl.visit(this);
        }
        exitScope(os);
        endDeclaration(od);
        */
        
        if (pls.isEmpty()) {
            if (type instanceof Tree.FunctionModifier) {
                type.addError("variables with no parameters may not be declared using the keyword 'function'");
            }
            if (type instanceof Tree.VoidModifier) {
                type.addError("variables with no parameters may not be declared using the keyword 'void'");
            }
        }
        else {
            Tree.ParameterList pl = pls.get(0);
            pl.addUnsupportedError("variables with parameter lists are not yet supported");
            if (type instanceof Tree.ValueModifier) {
                type.addError("variables with parameters may not be declared using the keyword 'value'");
            }
        }
                
        that.setScope(scope);
        that.setUnit(unit);
    }
    
    private static List<TypeParameter> 
    getTypeParameters(Tree.TypeParameterList tpl) {
        List<TypeParameter> typeParameters = emptyList();
        if (tpl!=null) {
            boolean foundDefaulted=false;
            List<Tree.TypeParameterDeclaration> tpds = 
                    tpl.getTypeParameterDeclarations();
            typeParameters = 
                    new ArrayList<TypeParameter>
                        (tpds.size());
            for (Tree.TypeParameterDeclaration tp: tpds) {
                typeParameters.add(tp.getDeclarationModel());
                if (tp.getTypeSpecifier()==null) {
                    if (foundDefaulted) {
                        tp.addError("required type parameter follows defaulted type parameter");
                    }
                }
                else {
                    foundDefaulted=true;
                }
            }
        }
        return typeParameters;
    }
    
    @Override
    public void visit(Tree.ModuleDescriptor that) {
        if (!unit.getFilename().equals("module.ceylon")) {
            that.addError("module descriptor must occur in a compilation unit named module.ceylon");
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.PackageDescriptor that) {
        if (!unit.getFilename().equals("package.ceylon")) {
            that.addError("package descriptor must occur in a compilation unit named package.ceylon");
        }
        super.visit(that);
    }
    
    @Override public void visit(Tree.Declaration that) {
        if (unit.getFilename().equals("module.ceylon") || 
            unit.getFilename().equals("package.ceylon")) {
            that.addError("declaration may not occur in a module or package descriptor file");
        }
        Declaration model = that.getDeclarationModel();
        Declaration d = beginDeclaration(model);
        super.visit(that);
        endDeclaration(d);
        if (model.isClassOrInterfaceMember()) {
            ClassOrInterface container = 
                    (ClassOrInterface) 
                        model.getContainer();
            if (container.isFinal() && model.isDefault()) {
                that.addError("member of final class may not be annotated 'default'", 
                        1350);
            }
        }
        if (model.isToplevel()) {
            String name = model.getName();
            if (name!=null && name.endsWith("_")) {
                that.addUnsupportedError("toplevel declaration name ending in _ not currently supported");
            }
            if (pkg.getNameAsString().endsWith("_")) {
                that.addUnsupportedError("toplevel declaration belonging to package with name ending in _ not currently supported");
            }
        }
    }

    private void handleDeclarationAnnotations(Tree.Declaration that,
            Declaration model) {
        Tree.AnnotationList al = that.getAnnotationList();
        if (hasAnnotation(al, "shared", unit)) {
            if (that instanceof Tree.AttributeSetterDefinition) {
                that.addError("setter may not be annotated 'shared'", 
                        1201);
            }
            /*else if (that instanceof Tree.TypedDeclaration && !(that instanceof Tree.ObjectDefinition)) {
                Tree.Type t =  ((Tree.TypedDeclaration) that).getType();
                if (t instanceof Tree.ValueModifier || t instanceof Tree.FunctionModifier) {
                    t.addError("shared declarations must explicitly specify a type", 200);
                }
                else {
                    model.setShared(true);
                }
            }*/
            else {
                model.setShared(true);
            }
        }
        if (hasAnnotation(al, "static", unit)) {
            if (model instanceof Function
             || model instanceof Value
             || model instanceof ClassOrInterface
             || model instanceof TypeAlias) {
                model.setStatic(true);
                if (model.isInterfaceMember()) {
                    that.addUnsupportedError("static members of interfaces are not yet supported");
                }
            }
            else {
                that.addError("declaration may not be annotated 'static'");
            }
        }
        if (hasAnnotation(al, "small", unit)) {
            if (model instanceof FunctionOrValue) {
                ((FunctionOrValue) model).setSmall(true);
            }
        }
        if (hasAnnotation(al, "default", unit)) {
            if (that instanceof Tree.ObjectDefinition) {
                that.addError("object declaration may not be annotated 'default'", 
                        1313);
            }
            /*else if (that instanceof Tree.Parameter) {
                that.addError("parameters may not be annotated 'default'", 1313);
            }*/
            else {
                model.setDefault(true);
            }
        }
        if (hasAnnotation(al, "formal", unit)) {
            if (that instanceof Tree.ObjectDefinition) {
                that.addError("object declaration may not be annotated 'formal'", 
                        1312);
            }
            else {
                model.setFormal(true);
            }
        }
        if (model.isFormal() && model.isDefault()) {
            that.addError("declaration may not be annotated both 'formal' and 'default'",
                    1320);
        }
        if (model.isStatic() && model.isActual()) {
            that.addError("static member may not be annotated 'actual'", 
                    1311);            
        }
        if (model.isStatic() && model.isFormal()) {
            that.addError("static member may not be annotated 'formal'", 
                    1312);
        }
        if (model.isStatic() && model.isDefault()) {
            that.addError("static member may not be annotated 'default'", 
                    1313);            
        }
        Tree.Annotation na = 
                getAnnotation(al, "native", unit);
        Backends backends = Backends.ANY;
        if (na != null) {
            int cnt = getAnnotationArgumentCount(na);
            if (cnt == 0) {
                backends = Backends.HEADER;
            } else {
                for (int i=0; i<cnt; i++) {
                    String be = getAnnotationArgument(na, i);
                    Backend backend = Backend.fromAnnotation(be);
                    if (!backend.isRegistered()) {
                        na.addError("illegal native backend name: '\"" +
                                be +
                                "\"' (must be either '\"jvm\"' or '\"js\"')");
                    }
                    backends = backends.merged(backend);
                }
            }
        }
        model.setNativeBackends(backends);
        if (model.isNative() && model.isFormal()) {
            that.addError("declaration may not be annotated both 'formal' and 'native'");
        }
        if (hasAnnotation(al, "actual", unit)) {
            model.setActual(true);
        }
        if (hasAnnotation(al, "abstract", unit)) {
            if (model instanceof Class) {
                ((Class) model).setAbstract(true);
            }
            else if (isConstructor(model)) {
                if (model instanceof Constructor) {
                    //ignore for now
                }
                else if (model instanceof Function) {
                    ((Constructor)((Function) model).getTypeDeclaration()).setAbstract(true);
                }
                else {
                    that.addError("declaration is not a callable constructor, and may not be annotated 'abstract'", 
                            1800);
                }
            }
            else {
                that.addError("declaration is not a class, and may not be annotated 'abstract'", 
                        1600);
            }
        }
        if (hasAnnotation(al, "final", unit)) {
            if (model instanceof ClassAlias) {
                that.addError("declaration is a class alias, and may not be annotated 'final'", 
                        1700);
            }
            else if (model instanceof Class) {
                ((Class) model).setFinal(true);
            }
            else {
                that.addError("declaration is not a class, and may not be annotated 'final'", 
                        1700);
            }
        }
        if (hasAnnotation(al, "sealed", unit)) {
            if (model instanceof ClassOrInterface) {
                ((ClassOrInterface) model).setSealed(true);
            }
            else if (ModelUtil.isConstructor(model)) {
                if (model instanceof Constructor) {
                    //ignore for now
                }
                else if (model instanceof Function) {
                    ((Function) model).getTypeDeclaration().setSealed(true);
                }
                else {
                    that.addError("declaration is not a callable constructor, and may not be annotated 'sealed'", 
                            1800);
                }
            }
            else {
                that.addError("declaration is not a class or interface, and may not be annotated 'sealed'", 
                        1800);
            }
        }
        if (hasAnnotation(al, "variable", unit)) {
            if (model instanceof Value) {
                ((Value) model).setVariable(true);
            }
            else {
                that.addError("declaration is not a value, and may not be annotated 'variable'", 
                        1500);
            }
        }
        if (hasAnnotation(al, "late", unit)) {
            if (model instanceof Value) {
                if (that instanceof Tree.AttributeDeclaration) {
                    Tree.AttributeDeclaration ad = 
                            (Tree.AttributeDeclaration) that;
                    if (ad.getSpecifierOrInitializerExpression()==null) {
                        ((Value) model).setLate(true);
                    }
                    else {
                        that.addError("value is not an uninitialized reference, and may not be annotated 'late'", 
                                1900);
                    }
                }
                else {
                    that.addError("value is not an uninitialized reference, and may not be annotated 'late'", 
                            1900);
                }
            }
            else {
                that.addError("declaration is not a value, and may not be annotated 'late'", 
                        1900);
            }
        }
        if (model instanceof Value) {
            Value value = (Value) model;
            if (value.isVariable() && value.isTransient()) {
                that.addError("getter may not be annotated 'variable' (define a setter with 'assign' instead)", 
                        1501);
            }
        }
        if (hasAnnotation(al, "deprecated", unit)) {
            model.setDeprecated(true);
        }
        if (hasAnnotation(al, "annotation", unit)) {
            if (!(model instanceof Function) && 
                !(model instanceof Class)) {
                that.addError("declaration is not a function or class, and may not be annotated 'annotation'", 
                        1950);
            }
            else if (!model.isToplevel()) {
                that.addError("declaration is not toplevel, and may not be annotated 'annotation'", 
                        1951);
            }
            else {
                model.setAnnotation(true);
            }
        }
        if (hasAnnotation(al, "serializable", unit)) {
            if (model instanceof Class) {
                ((Class) model).setSerializable(true);
            }
            else {
                that.addError("declaration is not a class, and may not be annotated 'serializable'", 
                        1600);
            }
        }
        if (hasAnnotation(al, "aliased", unit)) {
            Tree.Annotation aliased = 
                    getAnnotation(al, "aliased", unit);
            List<String> aliases = 
                    getAnnotationSequenceArgument(aliased);
            model.setAliases(aliases);
        }
        if (hasAnnotation(al, "service", unit)) {
            if (!(model instanceof Class) || !model.isToplevel()) {
                that.addError("declaration is not a toplevel class, and may not be annotated 'service'");
            }
            else if (!model.isShared()) {
                that.addError("class is not shared, and may not be annotated 'service'",
                        705);
            }
            else if (((Class) model).isAbstract()) {
                that.addError("class is abstract, and may not be annotated 'service'",
                        1601);
            }
        }
        buildAnnotations(al, model.getAnnotations());
    }

    public static void setVisibleScope(Declaration model) {
        ModelUtil.setVisibleScope(model);
    }

    private static void checkFormalMember(
            Tree.Declaration that, Declaration d) {
        
        Scope container = d.getContainer();
        if (d.isFormal()) {
            if (container instanceof ClassOrInterface) {
                //handled in RefinementVisitor
//                ClassOrInterface ci = (ClassOrInterface) d.getContainer();
//                if (!ci.isAbstract() && !ci.isFormal()) {
//                    that.addError("formal member belongs to a concrete class", 900);
//                }
            } 
            else {
                that.addError("formal member does not belong to an interface or abstract class", 
                        1100);
            }
            if (!(that instanceof Tree.AttributeDeclaration) && 
                !(that instanceof Tree.MethodDeclaration) &&
                !(that instanceof Tree.AnyClass)) {
                that.addError("formal member may not have a body", 
                        1101);
            }
        }
        
    }
        
    @Override public void visit(Tree.TypedArgument that) {
        Declaration d = 
                beginDeclaration(that.getDeclarationModel());
        super.visit(that);
        endDeclaration(d);
    }
    
    TypeParameter searchForTypeParameter(String name, Generic g) {
        for (TypeParameter tp: g.getTypeParameters()) {
            if (tp.isTypeConstructor()) {
                TypeParameter p = (TypeParameter) 
                        tp.getDirectMember(name, null, false);
                if (p==null) {
                    p = searchForTypeParameter(name, tp);
                }
                if (p!=null) {
                    return p;
                }
            }
        }
        return null;
    }
    
    @Override
    public void visit(Tree.TypeConstraint that) {
        String name = name(that.getIdentifier());
        TypeParameter p = (TypeParameter)
                scope.getDirectMember(name, null, false);
        if (p==null && scope instanceof Generic) {
            //TODO: just look at the most recent
            Generic g = (Generic) scope;
            p = searchForTypeParameter(name, g);
        }
        that.setDeclarationModel(p);
        if (p==null) {
            that.addError("no matching type parameter for constraint: '" + 
                    name + "'", 2500);
            p = new TypeParameter();
            p.setDeclaration(declaration);
            that.setDeclarationModel(p);
            visitDeclaration(that, p);
        }
        else {
            if (that.getTypeParameterList()!=null) {
                p.setTypeConstructor(true);
            }
        	if (p.isConstrained()) {
        		that.addError("duplicate constraint list for type parameter: '" +
        				name + "'");
        	}
        	p.setConstrained(true);
        }
        
        Scope o = enterScope(p);
        super.visit(that);
        exitScope(o);
        
        if (that.getAbstractedType()!=null) {
            that.addUnsupportedError("lower bound type constraints are not yet supported");
        }
    }

    @Override
    public void visit(Tree.ParameterizedExpression that) {
        super.visit(that);
        setParameterLists(null, that.getParameterLists(), that);
        if (!that.getLeftTerm()) {
            that.addError("parameterized expression not the target of a specification statement: parameter list is not followed by '=>' specifier (add '=>' specifier, or explicitly specify 'function' keyword or return type)");
        }
    }
    
    @Override
    public void visit(Tree.SpecifierStatement that) {
        Tree.Term lhs = that.getBaseMemberExpression();
        if (lhs instanceof Tree.ParameterizedExpression) {
            Tree.ParameterizedExpression pe = 
                    (Tree.ParameterizedExpression) lhs;
            pe.setLeftTerm(true);
        }
        Specification s = new Specification();
        s.setId(id++);
        visitElement(that, s);
        Scope o = enterScope(s);
        super.visit(that);
        exitScope(o);
    }
    
    /*@Override
    public void visit(Tree.SpecifiedArgument that) {
        Specification s = new Specification();
        visitElement(that, s);
        Scope o = enterScope(s);
        super.visit(that);
        exitScope(o);
    }*/
    
    @Override
    public void visit(Tree.SatisfiesCondition that) {
        super.visit(that);
        that.addUnsupportedError("satisfies conditions are not yet supported");
    }
    
    @Override
    public void visit(Tree.Assertion that) {
        Tree.ConditionList cl = that.getConditionList();
        if (cl!=null) {
            for (Tree.Condition c: cl.getConditions()) {
                c.setAssertion(true);
            }
        }
        Declaration d = beginDeclaration(null);
        super.visit(that);
        endDeclaration(d);
    }    
    
    @Override
    public void visit(Tree.Annotation that) {
        super.visit(that);
        that.getPrimary().setScope(pkg);
    }
    
    @Override
    public void visit(Tree.TypeArgumentList that) {
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.PositionalArgumentList that) {
        super.visit(that);
        checkPositionalArguments(that.getPositionalArguments());
    }
    
    @Override
    public void visit(Tree.SequencedArgument that) {
        super.visit(that);
        checkPositionalArguments(that.getPositionalArguments());
    }
    
    private void checkPositionalArguments(
            List<Tree.PositionalArgument> args) {
        for (int i=0; i<args.size()-1; i++) {
            Tree.PositionalArgument a = args.get(i);
            if (a instanceof Tree.SpreadArgument) {
                a.addError("spread argument must be the last argument in the argument list");
            }
            if (a instanceof Tree.Comprehension) {
                a.addError("comprehension must be the last argument in the argument list");
            }
        }
    }    
    
    @Override
    public void visit(Tree.TryCatchStatement that) {
        super.visit(that);
        if (that.getTryClause().getBlock()!=null &&
                that.getCatchClauses().isEmpty() && 
                that.getFinallyClause()==null && 
                that.getTryClause().getResourceList()==null) {
            that.addError("try must have a catch, finally, or resource expression");
        }
    }
    
    @Override
    public void visit(Tree.Import that) {
        super.visit(that);
        Tree.ImportPath path = that.getImportPath();
        if (path!=null && 
                formatPath(path.getIdentifiers())
                    .equals(LANGUAGE_MODULE_NAME)) {
            Tree.ImportMemberOrTypeList imtl = 
                    that.getImportMemberOrTypeList();
            if (imtl!=null) {
                for (Tree.ImportMemberOrType imt: 
                        imtl.getImportMemberOrTypes()) {
                    Tree.Alias a = imt.getAlias();
                    Tree.Identifier id = 
                            imt.getIdentifier();
                    if (a!=null && id!=null) {
                        Tree.Identifier aid = 
                                a.getIdentifier();
                        String name = name(id);
                        String alias = name(aid);
                        Map<String, String> mods = 
                                unit.getModifiers();
                        if (mods.containsKey(name)) {
                            mods.put(name, alias);
                        }
                    }
                }
            }
        }
    }
    
    private boolean declarationReference=false;
    
    @Override
    public void visit(Tree.MetaLiteral that) {
        declarationReference = 
                that instanceof Tree.ClassLiteral || 
                that instanceof Tree.InterfaceLiteral ||
                that instanceof Tree.NewLiteral ||
                that instanceof Tree.AliasLiteral ||
                that instanceof Tree.TypeParameterLiteral ||
                that instanceof Tree.ValueLiteral ||
                that instanceof Tree.FunctionLiteral;
        super.visit(that);
        declarationReference = false;
    }
    
    @Override
    public void visit(Tree.StaticType that) {
        that.setMetamodel(declarationReference);
        super.visit(that);
    }
    
    private boolean inExtends;
    
    @Override
    public void visit(Tree.BaseType that) {
        super.visit(that);
        final Scope scope = that.getScope();
        final String name = name(that.getIdentifier());
        if (inExtends) {
            final Tree.TypeArgumentList tal = 
                    that.getTypeArgumentList();
            final boolean packageQualified = 
                    that.getPackageQualified();
            Type t = 
                    new LazyType(unit) {
                @Override
                public TypeDeclaration initDeclaration() {
                    return packageQualified ?
                            getPackageTypeDeclaration(name, 
                                    null, false, unit) :
                            getTypeDeclaration(scope, name, 
                                    null, false, unit);
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    TypeDeclaration dec = getDeclaration();
                    List<TypeParameter> tps = 
                            dec.getTypeParameters();
                    return getTypeArgumentMap(dec, null,
                            AnalyzerUtil.getTypeArguments(
                                    tal, null, tps));
                }
                @Override
                public Map<TypeParameter, SiteVariance> 
                initVarianceOverrides() {
                    TypeDeclaration dec = getDeclaration();
                    List<TypeParameter> tps = 
                            dec.getTypeParameters();
                    return getVarianceMap(dec, null, 
                            AnalyzerUtil.getVariances(tal, tps));
                }
            };
            that.setTypeModel(t);
        }
    }
    
    @Override
    public void visit(Tree.QualifiedType that) {
        super.visit(that);
        final String name = name(that.getIdentifier());
        final Tree.StaticType outerType = 
                that.getOuterType();
        if (inExtends) {
            final Tree.TypeArgumentList tal = 
                    that.getTypeArgumentList();
            Type t = 
                    new LazyType(unit) {
                @Override
                public TypeDeclaration initDeclaration() {
                    if (outerType==null) {
                        return null;
                    }
                    else {
                        TypeDeclaration dec = 
                                outerType.getTypeModel()
                                    .getDeclaration();
                        return AnalyzerUtil.getTypeMember(
                                dec, name, null, false, unit, scope);
                    }
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    if (outerType==null) {
                        return emptyMap();
                    }
                    else {
                        TypeDeclaration dec = 
                                getDeclaration();
                        List<TypeParameter> tps = 
                                dec.getTypeParameters();
                        Type ot = 
                                outerType.getTypeModel();
                        return getTypeArgumentMap(dec, ot, 
                                AnalyzerUtil.getTypeArguments(
                                        tal, ot, tps));
                    }
                }
                @Override
                public Map<TypeParameter, SiteVariance> 
                initVarianceOverrides() {
                    TypeDeclaration dec = getDeclaration();
                    List<TypeParameter> tps = 
                            dec.getTypeParameters();
                    Type ot = 
                            outerType.getTypeModel();
                    return getVarianceMap(dec, ot, 
                            AnalyzerUtil.getVariances(tal, tps));
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(Tree.IterableType that) {
        super.visit(that);
        if (inExtends) {
            final Tree.Type elem = that.getElementType();
            Type t = 
                    new LazyType(unit) {
                Type iterableType() {
                    final Type elementType;
                    final boolean atLeastOne;
                    if (elem==null) {
                        elementType = unit.getNothingType();
                        atLeastOne = false;
                    }
                    else if (elem instanceof Tree.SequencedType) {
                        Tree.SequencedType set = 
                                (Tree.SequencedType) elem;
                        elementType = set.getType().getTypeModel();
                        atLeastOne = set.getAtLeastOne();
                    }
                    else {
                        elementType = null;
                        atLeastOne = false;
                    }
                    if (elementType!=null) {
                        return atLeastOne ? 
                                unit.getNonemptyIterableType(elementType) : 
                                unit.getIterableType(elementType);
                    }
                    else {
                        Type ut = 
                                new UnknownType(unit)
                                    .getType();
                        return unit.getIterableType(ut);
                    }
                }
                @Override
                public boolean isUnknown() {
                    return false;
                }                
                @Override
                public TypeDeclaration initDeclaration() {
                    return iterableType().getDeclaration();
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    return iterableType().getTypeArguments();
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(Tree.TupleType that) {
        super.visit(that);
        if (inExtends) {
            final List<Tree.Type> ets = 
                    that.getElementTypes();
            Type t = 
                    new LazyType(unit) {
                private Type tupleType() {
                    return getTupleType(ets, unit);
                }
                @Override
                public TypeDeclaration initDeclaration() {
                    return tupleType().getDeclaration();
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    return tupleType().getTypeArguments();
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(final Tree.OptionalType that) {
        super.visit(that);
        final Tree.StaticType definiteType = 
                that.getDefiniteType();
        if (inExtends) {
            Type t = 
                    new LazyType(unit) {
                @Override
                public TypeDeclaration initDeclaration() {
                    List<Type> types = 
                            new ArrayList<Type>(2);
                    types.add(unit.getNullType());
                    if (definiteType!=null) {
                        types.add(definiteType.getTypeModel());
                    }
                    UnionType ut = new UnionType(unit);
                    ut.setCaseTypes(types);
                    return ut;
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    return emptyMap();
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(final Tree.UnionType that) {
        super.visit(that);
        final List<Tree.StaticType> sts = 
                that.getStaticTypes();
        if (inExtends) {
            Type t = 
                    new LazyType(unit) {
                @Override
                public TypeDeclaration initDeclaration() {
                    List<Type> types = 
                            new ArrayList<Type>
                                (sts.size());
                    for (Tree.StaticType st: sts) {
                        Type t = st.getTypeModel();
                        if (t!=null) {
                            types.add(t);
                        }
                    }
                    UnionType ut = 
                            new UnionType(unit);
                    ut.setCaseTypes(types);
                    return ut;
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    return emptyMap();
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(Tree.IntersectionType that) {
        super.visit(that);
        if (inExtends) {
            final List<Tree.StaticType> sts = 
                    that.getStaticTypes();
            Type t = 
                    new LazyType(unit) {
                @Override
                public TypeDeclaration initDeclaration() {
                    List<Type> types = 
                            new ArrayList<Type>
                                (sts.size());
                    for (Tree.StaticType st: sts) {
                        Type t = st.getTypeModel();
                        if (t!=null) {
                            types.add(t);
                        }
                    }
                    IntersectionType it = 
                            new IntersectionType(unit);
                    it.setSatisfiedTypes(types);
                    return it;
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    return emptyMap();
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(Tree.SequenceType that) {
        super.visit(that);
        if (inExtends) {
            final Tree.StaticType elementType = 
                    that.getElementType();
            final Tree.NaturalLiteral length = 
                    that.getLength();
            Type t;
            if (length==null) {
                t = new LazyType(unit) {
                    @Override
                    public boolean isUnknown() {
                        return false;
                    }                    
                    @Override
                    public TypeDeclaration initDeclaration() {
                        return unit.getSequentialDeclaration();
                    }
                    @Override
                    public Map<TypeParameter, Type> 
                    initTypeArguments() {
                        List<TypeParameter> stps = 
                                unit.getSequentialDeclaration()
                                    .getTypeParameters();
                        return singletonMap(stps.get(0), 
                                elementType.getTypeModel());
                    }
                };
            }
            else {
                final int len;
                try {
                    len = parseInt(length.getText());
                }
                catch (NumberFormatException nfe) {
                    return;
                }
                if (len<1 || len>1000) {
                    return;
                }
                else {
                    t = new StaticLengthSequenceType(elementType, len);
                }
            }
            that.setTypeModel(t);
        }
    }
    
    private final class StaticLengthSequenceType 
            extends LazyType {
        private final Tree.StaticType elementType;
        private final int len;

        private StaticLengthSequenceType
                (Tree.StaticType elementType, int len) {
            super(unit);
            this.elementType = elementType;
            this.len = len;
        }
        
        @Override
        public boolean isUnknown() {
            return false;
        }
        
        @Override
        public TypeDeclaration initDeclaration() {
            return unit.getTupleDeclaration();
        }

        @Override
        public Map<TypeParameter,Type> 
        initTypeArguments() {
            List<TypeParameter> stps = 
                    unit.getTupleDeclaration()
                        .getTypeParameters();
            Map<TypeParameter,Type> args = 
                    new HashMap<TypeParameter,Type>(3);
            Type et = elementType.getTypeModel();
            args.put(stps.get(0), et);
            args.put(stps.get(1), et);
            args.put(stps.get(2), len==1 ? 
                    unit.getEmptyType() : 
                    new StaticLengthSequenceType(elementType, len-1));
            return args;
        }
    }

    public void visit(Tree.SequencedType that) {
        super.visit(that);
        if (inExtends) {
            final Type type = 
                    that.getType()
                        .getTypeModel();
            final boolean atLeastOne = that.getAtLeastOne();
            Type t = 
                    new LazyType(unit) {
                @Override
                public boolean isUnknown() {
                    return false;
                }
                @Override
                public TypeDeclaration initDeclaration() {
                    return atLeastOne ? 
                            unit.getSequenceDeclaration() : 
                            unit.getSequentialDeclaration();
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    Interface dec = 
                            atLeastOne ? 
                                    unit.getSequenceDeclaration() : 
                                    unit.getSequentialDeclaration();
                    List<TypeParameter> stps = 
                            dec.getTypeParameters();
                    return singletonMap(stps.get(0), type);
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(Tree.EntryType that) {
        super.visit(that);
        if (inExtends) {
            final Tree.StaticType keyType = 
                    that.getKeyType();
            final Tree.StaticType valueType = 
                    that.getValueType();
            Type t = 
                    new LazyType(unit) {
                @Override
                public boolean isUnknown() {
                    return false;
                }
                @Override
                public TypeDeclaration initDeclaration() {
                    return unit.getEntryDeclaration();
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    HashMap<TypeParameter, Type> map = 
                            new HashMap<TypeParameter, Type>();
                    List<TypeParameter> itps = 
                            unit.getEntryDeclaration()
                                .getTypeParameters();
                    map.put(itps.get(0), 
                            keyType.getTypeModel());
                    map.put(itps.get(1), 
                            valueType.getTypeModel());
                    return map;
                }
            };
            that.setTypeModel(t);
        }
    }
    
    public void visit(Tree.FunctionType that) {
        super.visit(that);
        if (inExtends) {
            final Tree.StaticType rt = that.getReturnType();
            if (rt!=null) {
                final List<Tree.Type> args = 
                        that.getArgumentTypes();
                Type t = 
                        new LazyType(unit) {
                    @Override
                    public boolean isUnknown() {
                        return false;
                    }
                    @Override
                    public TypeDeclaration initDeclaration() {
                        return unit.getCallableDeclaration();
                    }
                    @Override
                    public Map<TypeParameter, Type> 
                    initTypeArguments() {
                        HashMap<TypeParameter, Type> map = 
                                new HashMap<TypeParameter, Type>();
                        List<TypeParameter> ctps = 
                                unit.getCallableDeclaration()
                                    .getTypeParameters();
                        map.put(ctps.get(0), 
                                rt.getTypeModel());
                        map.put(ctps.get(1),
                                getTupleType(args, unit));
                        return map;
                    }
                };
                that.setTypeModel(t);
            }
        }
    }
    
    public void visit(Tree.TypeConstructor that) {
        TypeAlias ta = new TypeAlias();
        ta.setShared(true);
        ta.setName("Anonymous#"+fid++);
        ta.setAnonymous(true);
        visitElement(that, ta);
        setVisibleScope(ta);
        Scope o = enterScope(ta);
        Declaration od = beginDeclaration(ta);
        super.visit(that);
        endDeclaration(od);
        exitScope(o);
        
        ta.setExtendedType(that.getType().getTypeModel());
        that.setDeclarationModel(ta);
        Type pt = ta.getType();
        pt.setTypeConstructor(true);
        that.setTypeModel(pt);
    }
    
    public void visit(Tree.SuperType that) {
        super.visit(that);
        if (inExtends) {
            final Scope scope = that.getScope();
            Type t = 
                    new LazyType(unit) {
                @Override
                public TypeDeclaration initDeclaration() {
                    ClassOrInterface ci = 
                            getContainingClassOrInterface(scope);
                    if (ci==null) {
                        return null;
                    }
                    else {
                        if (ci.isClassOrInterfaceMember()) {
                            ClassOrInterface oci = 
                                    (ClassOrInterface) 
                                        ci.getContainer();
                            return intersectionOfSupertypes(oci)
                                    .getDeclaration();
                        }
                        else {
                            return null;
                        }
                    }
                }
                @Override
                public Map<TypeParameter, Type> 
                initTypeArguments() {
                    return emptyMap();
                }
            };
            that.setTypeModel(t);
        }
    }

    public void visit(Tree.GroupedType that) {
        super.visit(that);
        Tree.StaticType type = that.getType();
        if (type!=null) {
        	that.setTypeModel(type.getTypeModel());
        }
    }
    
    @Override
    public void visit(Tree.ExtendedType that) {
        inExtends = true;
        super.visit(that);
        inExtends = false;
        TypeDeclaration td = 
                (TypeDeclaration) 
                    that.getScope();
        if (!td.isAlias()) {
            Tree.SimpleType type = that.getType();
            if (type==null) {
                that.addError("missing extended type");
            }
            else {
                Type et = type.getTypeModel();
                if (et!=null) {
                    //we can't check here that it's a 
                    //sensible supertype, because this is
                    //just a lazy reference that will be
                    //resolvable later
                    td.setExtendedType(et);
                }
            }
        }
    }
    
    @Override
    public void visit(Tree.SatisfiedTypes that) {
        inExtends = true;
        super.visit(that);
        inExtends = false;
        TypeDeclaration td = 
                (TypeDeclaration) 
                    that.getScope();
        if (td.isAlias()) {
            return;
        }
        List<Tree.StaticType> types = that.getTypes();
        List<Type> list = 
                new ArrayList<Type>
                    (types.size());
        for (Tree.StaticType st: types) {
            if (st!=null) {
                Type type = st.getTypeModel();
                if (type!=null) {
                    //we can't check here that it's a 
                    //sensible supertype, because this is
                    //just a lazy reference that will be
                    //resolvable later
                    list.add(type);
                }
            }
        }
        td.setSatisfiedTypes(list);
    }
    
    @Override
    public void visit(Tree.ClassSpecifier that) {
        inExtends = true;
        super.visit(that);
        inExtends = false;
        Tree.SimpleType type = that.getType();
        if (type==null) {
            that.addError("missing aliased type");
        }
        else if (that.getInvocationExpression()==null) {
            that.addError("missing instantiation arguments");
        }
        else {
            TypeDeclaration td = 
                    (TypeDeclaration) 
                        that.getScope();
            Type at = type.getTypeModel();
            if (at!=null) {
                //we can't check here that it's a 
                //sensible supertype, because this is
                //just a lazy reference that will be
                //resolvable later
                td.setExtendedType(at);
            }
            checkSpecifierSymbol(that);
        }
    }
    
    @Override
    public void visit(Tree.TypeSpecifier that) {
        inExtends = true;
        super.visit(that);
        inExtends = false;
        Tree.StaticType type = that.getType();
        if (type==null) {
            that.addError("missing aliased type");
        }
        else if (!(that instanceof Tree.DefaultTypeArgument)) {
            TypeDeclaration td = 
                    (TypeDeclaration) 
                        that.getScope();
            Type at = type.getTypeModel();
            if (at!=null) {
                //we can't check here that it's a 
                //sensible supertype, because this is
                //just a lazy reference that will be
                //resolvable later
                td.setExtendedType(at);
            }
            checkSpecifierSymbol(that);
        }
    }
    
    @Override
    public void visit(Tree.LazySpecifierExpression that) {
        super.visit(that);
        checkSpecifierSymbol(that);
    }

    private static void checkSpecifierSymbol(Node that) {
        if (that.getMainToken().getType()==SPECIFY) {
            that.addError("incorrect syntax: expression must be specified using =>", 
                    1050);
        }
    }
    
}
