package com.xtremelabs.robolectric.bytecode;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.util.HashSet;
import java.util.Set;

public class MethodGenerator {
    private CtClass ctClass;

    public MethodGenerator(CtClass ctClass) {
        this.ctClass = ctClass;
    }

    public void fixCallsToSameMethodOnSuper(final CtMethod ctMethod) throws CannotCompileException {
        ctMethod.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall call) throws CannotCompileException {
                if (call.isSuper() && call.getMethodName().equals(ctMethod.getName())) {
                    StringBuilder buf = new StringBuilder();
                    try {
                        for (int i = 0; i < ctMethod.getParameterTypes().length; i++) {
                            if (buf.length() > 0) {
                                buf.append(", ");
                            }
                            buf.append("$");
                            buf.append(i + 1);
                        }

                        boolean returnsVoid = ctMethod.getReturnType().equals(CtClass.voidType);
                        call.replace(RobolectricInternals.class.getName() + ".directlyOn($0);\n" +
                                (returnsVoid ? "" : "$_ = ") + "super." + call.getMethodName() + "(" + buf.toString() + ");");
                    } catch (NotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
                super.edit(call);
            }
        });
    }

    public void fixConstructors() throws CannotCompileException, NotFoundException {
        boolean hasDefault = false;

        for (CtConstructor ctConstructor : ctClass.getDeclaredConstructors()) {
            try {
                fixConstructor(hasDefault, ctConstructor);

                if (ctConstructor.getParameterTypes().length == 0) {
                    hasDefault = true;
                    ctConstructor.setModifiers(Modifier.setPublic(ctConstructor.getModifiers()));
                }
            } catch (Exception e) {
                throw new RuntimeException("problem instrumenting " + ctConstructor, e);
            }
        }

        //System.err.println(ctClass.getName() + " hasDefault = " + hasDefault);

        if (!hasDefault) {
            String methodBody = generateConstructorBody(new CtClass[0]);
            ctClass.addConstructor(CtNewConstructor.make(new CtClass[0], new CtClass[0], "{\n" + methodBody + "}\n", ctClass));
        }
    }

    public void fixConstructor(boolean needsDefault, CtConstructor ctConstructor) throws NotFoundException, CannotCompileException {
        String methodBody = generateConstructorBody(ctConstructor.getParameterTypes());
        ctConstructor.setBody("{\n" + methodBody + "}\n");
    }

    public String generateConstructorBody(CtClass[] parameterTypes) throws NotFoundException {
        return generateMethodBody(
                new CtMethod(CtClass.voidType, "<init>", parameterTypes, ctClass),
                CtClass.voidType,
                Type.VOID,
                false,
                false);
    }

    public void fixMethods() throws NotFoundException, CannotCompileException {
        Set<String> instrumentedMethods = new HashSet<String>();

        for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
            fixMethod(ctMethod, true);
            instrumentedMethods.add(ctMethod.getName() + ctMethod.getSignature());
        }

        fixMethodIfNotAlreadyFixed(instrumentedMethods, "equals", "(Ljava/lang/Object;)Z");
        fixMethodIfNotAlreadyFixed(instrumentedMethods, "hashCode", "()I");
        fixMethodIfNotAlreadyFixed(instrumentedMethods, "toString", "()Ljava/lang/String;");
    }

    public void fixMethodIfNotAlreadyFixed(Set<String> instrumentedMethods, String methodName, String signature) throws NotFoundException {
        if (instrumentedMethods.add(methodName + signature)) {
            CtMethod equalsMethod = ctClass.getMethod(methodName, signature);
            fixMethod(equalsMethod, false);
        }
    }

    public static String describe(CtMethod ctMethod) throws NotFoundException {
        return Modifier.toString(ctMethod.getModifiers()) + " " + ctMethod.getReturnType().getSimpleName() + " " + ctMethod.getLongName();
    }

    public void fixMethod(final CtMethod ctMethod, boolean isDeclaredOnClass) throws NotFoundException {
        String describeBefore = describe(ctMethod);
        try {
            CtClass declaringClass = ctMethod.getDeclaringClass();
            int originalModifiers = ctMethod.getModifiers();

            if (isDeclaredOnClass) {
                fixCallsToSameMethodOnSuper(ctMethod);
            }

            boolean wasNative = Modifier.isNative(originalModifiers);
            boolean wasFinal = Modifier.isFinal(originalModifiers);
            boolean wasAbstract = Modifier.isAbstract(originalModifiers);
            boolean wasDeclaredInClass = ctClass == declaringClass;
//            if (wasDeclaredInClass != isDeclaredOnClass) {
//                throw new IllegalStateException(ctMethod.getLongName());
//            }

            if (wasFinal && ctClass.isEnum()) {
                return;
            }

            int newModifiers = originalModifiers;
            if (wasNative) {
                newModifiers = Modifier.clear(newModifiers, Modifier.NATIVE);
            }
            if (wasFinal) {
                newModifiers = Modifier.clear(newModifiers, Modifier.FINAL);
            }
            if (isDeclaredOnClass) {
                ctMethod.setModifiers(newModifiers);
            }

            CtClass returnCtClass = ctMethod.getReturnType();
            Type returnType = Type.find(returnCtClass);

            String methodName = ctMethod.getName();
            CtClass[] paramTypes = ctMethod.getParameterTypes();

            boolean isStatic = Modifier.isStatic(originalModifiers);
            String methodBody = generateMethodBody(ctMethod, wasNative, wasAbstract, returnCtClass, returnType, isStatic, !isDeclaredOnClass);

            if (!isDeclaredOnClass) {
                CtMethod newMethod = makeNewMethod(ctMethod, returnCtClass, methodName, paramTypes, "{\n" + methodBody + generateCallToSuper(ctMethod) + "\n}");
                newMethod.setModifiers(newModifiers);
                if (wasDeclaredInClass) {
                    ctMethod.insertBefore("{\n" + methodBody + "}\n");
                } else {
                    ctClass.addMethod(newMethod);
                }
            } else if (wasAbstract || wasNative) {
                CtMethod newMethod = makeNewMethod(ctMethod, returnCtClass, methodName, paramTypes, "{\n" + methodBody + "\n}");
                ctMethod.setBody(newMethod, null);
            } else {
                ctMethod.insertBefore("{\n" + methodBody + "}\n");
            }
        } catch (Exception e) {
            throw new RuntimeException("problem instrumenting " + describeBefore, e);
        }
    }

    public CtMethod makeNewMethod(CtMethod ctMethod, CtClass returnCtClass, String methodName, CtClass[] paramTypes, String methodBody) throws CannotCompileException, NotFoundException {
        return CtNewMethod.make(
                ctMethod.getModifiers(),
                returnCtClass,
                methodName,
                paramTypes,
                ctMethod.getExceptionTypes(),
                methodBody,
                ctClass);
    }

    public String generateCallToSuper(CtMethod ctMethod) throws NotFoundException {
        CtClass objectCtClass = ctClass.getClassPool().get(Object.class.getName());
        boolean superMethodIsInstrumented = !ctClass.getSuperclass().equals(objectCtClass);
        return (superMethodIsInstrumented ? RobolectricInternals.class.getName() + ".directlyOn($0);\n" : "") +
                "return super." + ctMethod.getName() + "(" + makeParameterReplacementList(ctMethod.getParameterTypes().length) + ");";
    }

    public String makeParameterReplacementList(int length) {
        if (length == 0) {
            return "";
        }

        String parameterReplacementList = "$1";
        for (int i = 2; i <= length; ++i) {
            parameterReplacementList += ", $" + i;
        }
        return parameterReplacementList;
    }

    public String generateMethodBody(CtMethod ctMethod, boolean wasNative, boolean wasAbstract, CtClass returnCtClass, Type returnType, boolean aStatic, boolean shouldGenerateCallToSuper) throws NotFoundException {
        String methodBody;
        if (wasAbstract) {
            methodBody = returnType.isVoid() ? "" : "return " + returnType.defaultReturnString() + ";";
        } else {
            methodBody = generateMethodBody(ctMethod, returnCtClass, returnType, aStatic, shouldGenerateCallToSuper);
        }

        if (wasNative && !shouldGenerateCallToSuper) {
            methodBody += returnType.isVoid() ? "" : "return " + returnType.defaultReturnString() + ";";
        }
        return methodBody;
    }

    public String generateMethodBody(CtMethod ctMethod, CtClass returnCtClass, Type returnType, boolean isStatic, boolean shouldGenerateCallToSuper) throws NotFoundException {
        boolean returnsVoid = returnType.isVoid();
        String className = ctClass.getName();

        String methodBody;
        StringBuilder buf = new StringBuilder();
        buf.append("if (!");
        generateCallToShouldCallDirectory(isStatic, className, buf);
        buf.append(") {\n");

        if (!returnsVoid) {
            buf.append("Object x = ");
        }
        generateCallToMethodInvoked(ctMethod, isStatic, className, buf);

        if (!returnsVoid) {
            buf.append("if (x != null) return ((");
            buf.append(returnType.nonPrimitiveClassName(returnCtClass));
            buf.append(") x)");
            buf.append(returnType.unboxString());
            buf.append(";\n");
            if (shouldGenerateCallToSuper) {
                buf.append(generateCallToSuper(ctMethod));
            } else {
                buf.append("return ");
                buf.append(returnType.defaultReturnString());
                buf.append(";\n");
            }
        } else {
            buf.append("return;\n");
        }

        buf.append("}\n");

        methodBody = buf.toString();
        return methodBody;
    }

    public void generateCallToShouldCallDirectory(boolean isStatic, String className, StringBuilder buf) {
        buf.append(RobolectricInternals.class.getName());
        buf.append(".shouldCallDirectly(");
        buf.append(isStatic ? className + ".class" : "this");
        buf.append(")");
    }

    public void generateCallToMethodInvoked(CtMethod ctMethod, boolean isStatic, String className, StringBuilder buf) throws NotFoundException {
        buf.append(RobolectricInternals.class.getName());
        buf.append(".methodInvoked(\n  ");
        buf.append(className);
        buf.append(".class, \"");
        buf.append(ctMethod.getName());
        buf.append("\", ");
        if (!isStatic) {
            buf.append("this");
        } else {
            buf.append("null");
        }
        buf.append(", ");

        appendParamTypeArray(buf, ctMethod);
        buf.append(", ");
        appendParamArray(buf, ctMethod);

        buf.append(")");
        buf.append(";\n");
    }

    public void appendParamTypeArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        CtClass[] parameterTypes = ctMethod.getParameterTypes();
        if (parameterTypes.length == 0) {
            buf.append("new String[0]");
        } else {
            buf.append("new String[] {");
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append("\"");
                CtClass parameterType = parameterTypes[i];
                buf.append(parameterType.getName());
                buf.append("\"");
            }
            buf.append("}");
        }
    }

    public void appendParamArray(StringBuilder buf, CtMethod ctMethod) throws NotFoundException {
        int parameterCount = ctMethod.getParameterTypes().length;
        if (parameterCount == 0) {
            buf.append("new Object[0]");
        } else {
            buf.append("new Object[] {");
            for (int i = 0; i < parameterCount; i++) {
                if (i > 0) buf.append(", ");
                buf.append(RobolectricInternals.class.getName());
                buf.append(".autobox(");
                buf.append("$").append(i + 1);
                buf.append(")");
            }
            buf.append("}");
        }
    }
}