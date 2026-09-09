package com.tinsl.nitro.resources;

import java.nio.file.Path;

/**
 * Implemented by the mixin on {@code PathPackResources} so Nitro can read a pack's root path
 * without another reflective field lookup. The field is private in vanilla and the mixin already
 * shadows it, so this just exposes what is there.
 */
public interface PackRoot {
    /** The pack's root, inside its jar file system for a jar pack. */
    Path nitro$root();
}
