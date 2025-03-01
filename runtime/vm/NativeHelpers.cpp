/*******************************************************************************
 * Copyright (c) 2001, 2022 IBM Corp. and others
 *
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which accompanies this
 * distribution and is available at https://www.eclipse.org/legal/epl-2.0/
 * or the Apache License, Version 2.0 which accompanies this distribution and
 * is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * This Source Code may also be made available under the following
 * Secondary Licenses when the conditions for such availability set
 * forth in the Eclipse Public License, v. 2.0 are satisfied: GNU
 * General Public License, version 2 with the GNU Classpath
 * Exception [1] and GNU General Public License, version 2 with the
 * OpenJDK Assembly Exception [2].
 *
 * [1] https://www.gnu.org/software/classpath/license.html
 * [2] http://openjdk.java.net/legal/assembly-exception.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 OR LicenseRef-GPL-2.0 WITH Assembly-exception
 *******************************************************************************/

#include "j9protos.h"
#include "j9consts.h"
#include "j9port.h"
#include "vm_internal.h"
#include "ut_j9vm.h"
#include "VMHelpers.hpp"
#include "ArrayCopyHelpers.hpp"
#include "j9vmnls.h"

extern "C" {

j9object_t   
getInterfacesHelper(J9VMThread *currentThread, j9object_t clazz)
{
	J9JavaVM *vm = currentThread->javaVM;
	j9object_t array = NULL;
	J9Class *javaLangClass = NULL;
	J9Class *arrayClass = NULL;
	J9ROMClass *romClass = NULL;
	U_32 interfaceCount = 0;
	J9SRP *interfaces = NULL;

	J9Class *j9clazz = J9VM_J9CLASS_FROM_HEAPCLASS(currentThread, clazz);
	if (NULL == j9clazz) {
		setCurrentExceptionUTF(currentThread, J9VMCONSTANTPOOL_JAVALANGNULLPOINTEREXCEPTION, NULL);
		goto done;
	}

	/* Get the class for java/lang/Class we know it will be loaded by this point and in the known class table */
	javaLangClass = J9VMJAVALANGCLASS_OR_NULL(vm);
	/* Get the array class or create it */
	arrayClass = javaLangClass->arrayClass;
	if (NULL == arrayClass) {
		J9ROMImageHeader *romHeader = vm->arrayROMClasses;
		arrayClass = internalCreateArrayClass(currentThread, (J9ROMArrayClass*)J9ROMIMAGEHEADER_FIRSTCLASS(romHeader), javaLangClass);
		if (VM_VMHelpers::exceptionPending(currentThread)) {
			goto done;
		}
	}

	/* Allocate the array of [Ljava/lang/Class to hold interfaceCount elements */
	romClass = j9clazz->romClass;
	interfaceCount = romClass->interfaceCount;
	array = vm->memoryManagerFunctions->J9AllocateIndexableObject(currentThread, arrayClass, interfaceCount, J9_GC_ALLOCATE_OBJECT_NON_INSTRUMENTABLE);
	if (J9_UNEXPECTED(NULL == array)) {
		setHeapOutOfMemoryError(currentThread);
		goto done;
	}

	/* Loop over the interfaces and store the class objects into the array */
	interfaces = J9ROMCLASS_INTERFACES(romClass);
	for (UDATA i = 0; i < interfaceCount; i++, interfaces++) {
		J9UTF8 * interfaceName = NNSRP_PTR_GET(interfaces, J9UTF8 *);
		J9Class* interfaceClass = internalFindClassUTF8(currentThread, J9UTF8_DATA(interfaceName), J9UTF8_LENGTH(interfaceName), j9clazz->classLoader, J9_FINDCLASS_FLAG_EXISTING_ONLY);
		/* Storing a class, which is immortal, into a newly-allocated array, so no exception can occur */
		J9JAVAARRAYOFOBJECT_STORE(currentThread, array, i, J9VM_J9CLASS_TO_HEAPCLASS(interfaceClass));
	}
done:
	return array;
}

UDATA
cInterpGetStackClassJEP176Iterator(J9VMThread * currentThread, J9StackWalkState * walkState)
{
	J9JavaVM * vm = currentThread->javaVM;
	J9Class * currentClass = J9_CLASS_FROM_CP(walkState->constantPool);
	J9InternalVMFunctions *vmFuncs = vm->internalVMFunctions;

	Assert_VM_mustHaveVMAccess(currentThread);

	if (J9_ARE_ALL_BITS_SET(J9_ROM_METHOD_FROM_RAM_METHOD(walkState->method)->modifiers, J9AccMethodFrameIteratorSkip)) {
		/* Skip methods with java.lang.invoke.FrameIteratorSkip / jdk.internal.vm.annotation.Hidden / java.lang.invoke.LambdaForm$Hidden annotation */
		return J9_STACKWALK_KEEP_ITERATING;
	}

	switch((UDATA)walkState->userData1) {
	case 1:
		/* Caller must be annotated with @sun.reflect.CallerSensitive annotation */
		if (((vm->systemClassLoader != currentClass->classLoader) && (vm->extensionClassLoader != currentClass->classLoader))
				|| J9_ARE_NO_BITS_SET(J9_ROM_METHOD_FROM_RAM_METHOD(walkState->method)->modifiers, J9AccMethodCallerSensitive)
		) {
			walkState->userData3 = (void *) TRUE;
			return J9_STACKWALK_STOP_ITERATING;
		}
		break;
	case 0:
		if ((walkState->method == vm->jliMethodHandleInvokeWithArgs)
				|| (walkState->method == vm->jliMethodHandleInvokeWithArgsList)
				|| (walkState->method == vm->jlrMethodInvoke)
#if JAVA_SPEC_VERSION >= 18
				|| (walkState->method == vm->jlrMethodInvokeMH)
#endif /* JAVA_SPEC_VERSION >= 18 */
				|| (vm->srMethodAccessor && vmFuncs->instanceOfOrCheckCast(currentClass, J9VM_J9CLASS_FROM_HEAPCLASS(currentThread, *((j9object_t*) vm->srMethodAccessor))))
				|| (vm->srConstructorAccessor && vmFuncs->instanceOfOrCheckCast(currentClass, J9VM_J9CLASS_FROM_HEAPCLASS(currentThread, *((j9object_t*) vm->srConstructorAccessor))))
		) {
			/* skip reflection classes and MethodHandle.invokeWithArguments() when reaching depth 0 */
			return J9_STACKWALK_KEEP_ITERATING;
		}

		walkState->userData2 = J9VM_J9CLASS_TO_HEAPCLASS(currentClass);
		return J9_STACKWALK_STOP_ITERATING;
	}

	walkState->userData1 = (void *) (((UDATA) walkState->userData1) - 1);
	return J9_STACKWALK_KEEP_ITERATING;
}

char*
convertByteArrayToCString(J9VMThread *currentThread, j9object_t byteArray)
{
	PORT_ACCESS_FROM_VMC(currentThread);
	UDATA size = (UDATA)J9INDEXABLEOBJECT_SIZE(currentThread, byteArray);
	char *result = (char*)j9mem_allocate_memory(size + 1, OMRMEM_CATEGORY_VM);
	if (NULL != result) {
		VM_ArrayCopyHelpers::memcpyFromArray(currentThread, byteArray, (UDATA)0, 0, size, (void*)result);
		result[size] = '\0';
	}
	return result;
}

j9object_t
convertCStringToByteArray(J9VMThread *currentThread, const char *cString)
{
	J9JavaVM *vm = currentThread->javaVM;
	U_32 size = (U_32)strlen(cString);
	j9object_t result = vm->memoryManagerFunctions->J9AllocateIndexableObject(currentThread, vm->byteArrayClass, size, J9_GC_ALLOCATE_OBJECT_NON_INSTRUMENTABLE);
	if (NULL != result) {
		VM_ArrayCopyHelpers::memcpyToArray(currentThread, result, (UDATA)0, 0, size, (void*)cString);
	}
	return result;
}

U_64 *
convertToNativeArgArray(J9VMThread *currentThread, j9object_t argArray, U_64 *ffiArgs)
{
	UDATA argCount = (UDATA)J9INDEXABLEOBJECT_SIZE(currentThread, argArray);
	/* 3 means each element of the array to be copied is 8 bytes (64bits) in size
	 * as specified in memcpyToOrFromArrayContiguous() at ArrayCopyHelpers.hpp.
	 * Note: all parameters are converted to long in InternalDowncallHandler,
	 * so the size of element in the argument array must be 64bits.
	 */
	VM_ArrayCopyHelpers::memcpyFromArray(currentThread, argArray, 3, 0, argCount, (void*)ffiArgs);
	return ffiArgs;
}

#if defined(J9VM_OPT_METHOD_HANDLE)
J9SFMethodTypeFrame *
buildMethodTypeFrame(J9VMThread * currentThread, j9object_t methodType)
{
#define ROUND_U32_TO(granularity, number) (((number) + (granularity) - 1) & ~((U_32)(granularity) - 1))
	U_32 argSlots = (U_32)J9VMJAVALANGINVOKEMETHODTYPE_ARGSLOTS(currentThread, methodType);
	j9object_t stackDescriptionBits = J9VMJAVALANGINVOKEMETHODTYPE_STACKDESCRIPTIONBITS(currentThread, methodType);
	U_32 descriptionInts = J9INDEXABLEOBJECT_SIZE(currentThread, stackDescriptionBits);
	U_32 descriptionBytes = ROUND_U32_TO(sizeof(UDATA), descriptionInts * sizeof(I_32));
	I_32 * description = NULL;
	U_32 i = 0;
	J9SFMethodTypeFrame * methodTypeFrame = NULL;
	UDATA * newA0 = currentThread->sp + argSlots;

	/* Push the description bits */

	description = (I_32 *) ((U_8 *)currentThread->sp - descriptionBytes);
	for (i = 0; i < descriptionInts; ++i) {
		description[i] = J9JAVAARRAYOFINT_LOAD(currentThread, stackDescriptionBits, i);
	}

	/* Push the frame */

	methodTypeFrame = (J9SFMethodTypeFrame *) ((U_8 *)description - sizeof(J9SFMethodTypeFrame));
	methodTypeFrame->methodType = methodType;
	methodTypeFrame->argStackSlots = argSlots;
	methodTypeFrame->descriptionIntCount = descriptionInts;
	methodTypeFrame->specialFrameFlags = 0;
	methodTypeFrame->savedCP = currentThread->literals;
	methodTypeFrame->savedPC = currentThread->pc;
	methodTypeFrame->savedA0 = (UDATA *) ((UDATA)currentThread->arg0EA | J9SF_A0_INVISIBLE_TAG);

	/* Update VM thread to point to new frame */

	currentThread->sp = (UDATA *) methodTypeFrame;
	currentThread->pc = (U_8 *) J9SF_FRAME_TYPE_METHODTYPE;
	currentThread->literals = NULL;
	currentThread->arg0EA = newA0;

	return methodTypeFrame;
#undef ROUND_U32_TO
}
#endif /* defined(J9VM_OPT_METHOD_HANDLE) */

} /* extern "C" */
