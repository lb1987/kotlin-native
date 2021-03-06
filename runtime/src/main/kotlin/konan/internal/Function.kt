/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package konan.internal

/**
 * Represents the reference to callable without any bound arguments.
 * It is supposed to be represented at runtime as native pointer to code.
 *
 * TODO: it seems to be very closely related to native interop types.
 */
abstract class UnboundCallableReference internal constructor()

/**
 * Values of function types are represented at runtime as instances of this class (and its subclasses).
 *
 * [FunctionImpl] is a reference to callable with itself as single bound argument,
 * i.e. when calling this function with arguments `args`, [unboundRef] is called with arguments `(this, *args)`.
 * So the instance is supposed to contain all implicit (bound) arguments of target function, e.g. captured values.
 */
open class FunctionImpl(val unboundRef: UnboundCallableReference)

/**
 * Simple [FunctionImpl] implementation: all bound arguments of target callable to be placed into [boundArgs].
 */
class SimpleFunctionImpl(unboundRef: UnboundCallableReference, vararg val boundArgs: Any?) : FunctionImpl(unboundRef)