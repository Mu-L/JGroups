package org.jgroups.util;

import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Privileged actions for the package
 *
 * Do not move. Do not change class and method visibility to avoid being called from other
 * {@link java.security.CodeSource}s, thus granting privilege escalation to external code.
 *
 * @author Scott.Stark@jboss.org
 * @since 4.2
 */
final class SecurityActions {

   interface SysProps {

      SysProps NON_PRIVILEGED = new SysProps() {
         @Override
         public String getProperty(final String name, final String defaultValue) {
            return System.getProperty(name, defaultValue);
         }

         @Override
         public String getProperty(final String name) {
            return System.getProperty(name);
         }

         @Override
         public String getEnv(String name) {
            return System.getenv(name);
         }
      };

      SysProps PRIVILEGED = new SysProps() {
         @Override
         public String getProperty(final String name, final String defaultValue) {
            return AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getProperty(name, defaultValue));
         }

         @Override
         public String getProperty(final String name) {
            return AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getProperty(name));
         }

         @Override
         public String getEnv(final String name) {
            return AccessController.doPrivileged((PrivilegedAction<String>) () -> System.getenv(name));
         }
      };

      String getProperty(String name, String defaultValue);

      String getProperty(String name);

      String getEnv(String name);
   }

   public static String getProperty(String name, String defaultValue) {
      return SysProps.NON_PRIVILEGED.getProperty(name, defaultValue);
   }

   public static String getProperty(String name) {
      return SysProps.NON_PRIVILEGED.getProperty(name);
   }

   public static String getEnv(String name) {
      return SysProps.NON_PRIVILEGED.getEnv(name);
   }
}
