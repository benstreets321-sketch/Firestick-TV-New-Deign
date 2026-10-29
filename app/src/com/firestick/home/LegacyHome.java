package com.firestick.home;

/**
 * My Home as an ordinary app, for Fire OS 6. That system immediately bounces a third-party
 * activity registered as a home screen (CATEGORY_HOME) back to Amazon's launcher, so on Fire OS 6
 * {@link Home} turns itself off and this copy, which has no HOME category, is what the
 * {@link Keeper} keeps in front of Amazon's home screen.
 */
public final class LegacyHome extends Home {}
