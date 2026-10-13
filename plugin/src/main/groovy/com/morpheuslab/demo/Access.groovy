package com.morpheuslab.demo

import com.morpheusdata.model.User

/** Role permission "Morpheus Demo": none, read (see the dashboard) or full (also Set up, Remove, delete apps). */
class Access {
    static String level(User user) { user?.permissions?.get(DemoPlugin.CODE) ?: 'none' }
    static boolean canRead(User user) { level(user) in ['read', 'full'] }
    static boolean canManage(User user) { level(user) == 'full' }
}
