// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.ast;
// SPDX-License-Identifier: Apache-2.0

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class ASTHierarchyContract {
    private static int checks;
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        checks++;
    }
    private static void reject(Class<? extends Throwable> type, Runnable action, String reason) {
        try { action.run(); }
        catch (Throwable actual) { check(type.isInstance(actual), reason+" got "+actual); return; }
        throw new AssertionError("accepted invalid "+reason);
    }
    private static String sha(int i) { return "%064x".formatted((long) i + 1); }
    private static ASTHierarchy.FileFact fact(int i) {
        String project = "project" + (i % 4);
        String module = "module" + ((i / 4) % 5);
        String pkg = "jdk.m3.family" + ((i / 20) % 13);
        String path = "src/main/java/Family" + i + ".java";
        int len = 1 + i % 17;
        int[] handles = new int[len];
        byte[] masks = new byte[len];
        for (int j = 0; j < len; j++) { handles[j] = 1 + j * 3 + i * 19; masks[j] = (byte) (i + j * 17); }
        return new ASTHierarchy.FileFact(project,module,pkg,path,sha(i),sha(i+10000),
                1 << (i % 13),handles,masks);
    }
    private static void coverage(ASTHierarchy result) {
        for (int lvl=0;lvl<6;lvl++) {
            var level = result.level(ASTHierarchy.Level.values()[lvl]);
            check(level.size()>0,"nonempty L"+lvl);
            int position=0;
            for (int i=0;i<level.size();i++) {
                check(level.firstChild(i)==position,"contiguous L"+lvl);
                check(level.childCount(i)>0,"nonempty child range L"+lvl);
                check(level.root(i).matches("[0-9a-f]{64}"),"hash L"+lvl);
                check(level.occurrenceCount(i)>0,"occurrence count L"+lvl);
                for (int j=0;j<level.childCount(i);j++) {
                    check(level.childOrdinal(i,j)==position+j,"indexed child L"+lvl);
                }
                position+=level.childCount(i);
            }
            int expected = lvl==0 ? result.totalOccurrences()
                    : result.level(ASTHierarchy.Level.values()[lvl-1]).size();
            check(position==expected,"parent child coverage L"+lvl);
        }
    }

    /** Unnamed module/package and non-collection parser facts are valid L0 leaves. */
    private static void genericSourceFacts() {
        var plain = new ASTHierarchy.FileFact("p", "", "", "Demo.java",
                sha(20000), sha(20001), 0, new int[] {7, 11},
                new byte[] {(byte) 0x80, (byte) 0xff});
        var javaType = new ASTHierarchy.FileFact("p", "jdk.compiler", "com.m3", "src/T.java",
                sha(20002), sha(20003), ASTHierarchy.LIST,
                new int[] {13}, new byte[] {1});
        var neutral = ASTHierarchy.build("r", List.of(plain));
        check(neutral.totalFiles() == 1 && neutral.totalOccurrences() == 2,
                "unnamed source retains two primitive occurrences");
        check(neutral.familyMask() == 0, "plain source does not claim collections");
        check(neutral.stateMask(0) == 128 && neutral.stateMask(1) == 255,
                "full unsigned masks preserved");
        neutral.requireFamilies(0);
        reject(IllegalStateException.class, () -> neutral.requireFamilies(ASTHierarchy.LIST),
                "plain source does not qualify a collection family");
        var mixed = ASTHierarchy.build("r", List.of(plain, javaType));
        check(mixed.rootSha256().equals(
                ASTHierarchy.build("r", List.of(javaType, plain)).rootSha256()),
                "mixed parser-fact hierarchy order independent");
        check(mixed.totalFiles() == 2 && mixed.totalOccurrences() == 3,
                "mixed leaf offsets remain exact");
        check(mixed.familyMask() == ASTHierarchy.LIST, "zero family does not add flags");
        check(mixed.level(ASTHierarchy.Level.L2_PACKAGE).size() == 2,
                "named and unnamed package planes");
        check(mixed.level(ASTHierarchy.Level.L3_MODULE).size() == 2,
                "named and unnamed module planes");
        check(mixed.level(ASTHierarchy.Level.L4_PROJECT).size() == 1,
                "same project retained");
        mixed.requireFamilies(ASTHierarchy.LIST);
        coverage(mixed);
        coverage(neutral);
        var typed = new ASTHierarchy.FileFact("p", "", "", "Demo.java",
                sha(20000), sha(20001), ASTHierarchy.ARRAY,
                new int[] {7, 11}, new byte[] {(byte) 0x80, (byte) 0xff});
        check(!neutral.rootSha256().equals(ASTHierarchy.build("r", List.of(typed)).rootSha256()),
                "family classification changes source digest");
        reject(IllegalArgumentException.class,
                () -> new ASTHierarchy.FileFact("p", " ", "", "Demo.java",
                        sha(20000), sha(20001), 0, new int[] {1}, new byte[] {0}),
                "space-only module still rejected");
        reject(IllegalArgumentException.class,
                () -> new ASTHierarchy.FileFact("p", "", " ", "Demo.java",
                        sha(20000), sha(20001), 0, new int[] {1}, new byte[] {0}),
                "space-only package still rejected");
        reject(IllegalArgumentException.class,
                () -> new ASTHierarchy.FileFact("p", null, "", "Demo.java",
                        sha(20000), sha(20001), 0, new int[] {1}, new byte[] {0}),
                "null module still rejected");
    }
    public static void main(String[] args) {
        int count = args.length==0?2000:Integer.parseInt(args[0]);
        var facts = new ArrayList<ASTHierarchy.FileFact>(count);
        int expectedAtoms=0;
        for(int i=0;i<count;i++){facts.add(fact(i));expectedAtoms += 1+i%17;}
        genericSourceFacts();
        var original=ASTHierarchy.build("hsoliwal/com.synexia",facts);
        check(original.totalOccurrences()==expectedAtoms,"total atom count");
        check(original.totalFiles()==count,"file count");
        if (count >= 260) {
            check(original.level(ASTHierarchy.Level.L2_PACKAGE).size()==260,"package fan-in");
            check(original.level(ASTHierarchy.Level.L3_MODULE).size()==20,"module fan-in");
            check(original.level(ASTHierarchy.Level.L4_PROJECT).size()==4,"project fan-in");
        }
        if (count>=13)original.requireFamilies(ASTHierarchy.ALL_COLLECTION_FAMILIES);
        coverage(original);
        String baseline=original.rootSha256();
        check(baseline.matches("[0-9a-f]{64}"),"root hash");
        var rnd=new Random(0x53a0L);
        for(int n=0;n<32;n++) {
            Collections.shuffle(facts,rnd);
            var replay=ASTHierarchy.build("hsoliwal/com.synexia",facts);
            check(replay.rootSha256().equals(baseline),"permutation root");
            check(replay.totalOccurrences()==expectedAtoms,"permutation count");
            check(replay.familyMask()==original.familyMask(),"permutation families");
        }
        for(int i=0;i<5;i++) {
            var first = facts.get(i);
            int[] h=first.atomHandles(); byte[] s=first.atomStateMasks();
            check(h.length==s.length,"aligned getters");
            h[0]=123456; s[0]=127;
            check(first.atomHandles()[0]!=123456,"not reached");
        }
        // Test input arrays cannot become later mutable ownership of retained coordinates.
        int[] handles={1,2,3};byte[] flags={1,0,-1};
        var sealed=new ASTHierarchy.FileFact("project","module","pkg","File.java",
                sha(6000),sha(6001),ASTHierarchy.MAP,handles,flags);
        handles[0]=999;flags[0]=0;
        check(sealed.atomHandles()[0]==1,"defensive input atom handles");
        check(sealed.atomStateMasks()[0]==1,"defensive input masks");
        sealed.atomHandles()[0]=1000;sealed.atomStateMasks()[0]=42;
        check(sealed.atomHandles()[0]==1,"defensive output atom handles");
        var one=ASTHierarchy.build("r",List.of(sealed));
        check(one.totalOccurrences()==3,"one-file hierarchy");
        check(one.atomHandle(0)==1,"flat atom coordinates");
        check(one.stateMask(2)==255,"unsigned mask view");
        check(one.level(ASTHierarchy.Level.L2_PACKAGE).size()==1,"one package");
        check(one.level(ASTHierarchy.Level.L3_MODULE).size()==1,"one module");
        check(one.level(ASTHierarchy.Level.L4_PROJECT).size()==1,"one project");
        check(one.level(ASTHierarchy.Level.L5_REPOSITORY).size()==1,"one repository");
        reject(IllegalStateException.class,()->one.requireFamilies(ASTHierarchy.SET),"coverage omitted");
        reject(IllegalArgumentException.class,()->ASTHierarchy.build("r", List.of(sealed,sealed)),"duplicate file");
        reject(IllegalArgumentException.class,()->ASTHierarchy.build("r",List.of()),"empty repo");
        reject(IllegalArgumentException.class,()->ASTHierarchy.build("",List.of(sealed)),"blank repo");
        reject(IllegalArgumentException.class,()->new ASTHierarchy.FileFact("p","m","pkg","../escape",sha(1),sha(2),1,new int[]{1},new byte[]{1}),"traversal");
        reject(IllegalArgumentException.class,()->new ASTHierarchy.FileFact("p","m","pkg","foo\\bar",sha(1),sha(2),1,new int[]{1},new byte[]{1}),"windows path");
        reject(IllegalArgumentException.class,()->new ASTHierarchy.FileFact("p","m","pkg","foo",sha(1),sha(2),1,new int[]{1},new byte[]{1,2}),"misaligned flags");
        reject(IllegalArgumentException.class,()->new ASTHierarchy.FileFact("p","m","pkg","foo",sha(1),sha(2),1,new int[]{0},new byte[]{1}),"invalid atom handle");
        reject(IllegalArgumentException.class,()->new ASTHierarchy.FileFact("p","m","pkg","foo",sha(1),sha(2),1<<22,new int[]{1},new byte[]{1}),"unknown family mask");
        reject(IllegalArgumentException.class,()->new ASTHierarchy.FileFact("p","m","pkg","foo","not-a-hash",sha(2),1,new int[]{1},new byte[]{1}),"bad source seal");
        // Tests a single atom state or receipt drift changes the content-addressed root.
        var changed = new ASTHierarchy.FileFact("project","module","pkg","File.java",
                sha(6000),sha(6001),ASTHierarchy.MAP,new int[]{1,2,3},new byte[]{2,0,-1});
        check(!one.rootSha256().equals(ASTHierarchy.build("r",List.of(changed)).rootSha256()),"state drift hash");
        var changedReceipt = new ASTHierarchy.FileFact("project","module","pkg","File.java",
                sha(6000),sha(7001),ASTHierarchy.MAP,new int[]{1,2,3},new byte[]{1,0,-1});
        check(!one.rootSha256().equals(ASTHierarchy.build("r",List.of(changedReceipt)).rootSha256()),"receipt drift hash");
        // Validate the retained layout never has an entry, tree node or per-atom object array.
        for (Class<?> clazz:List.of(ASTHierarchy.class, ASTHierarchy.Plane.class)) {
            for(Field f:clazz.getDeclaredFields()) {
                if(Modifier.isStatic(f.getModifiers()))continue;
                String type=f.getType().getTypeName();
                check(!type.contains("Entry")&&!type.contains("Node")&&!type.contains("Map"),"no entry/node fields");
            }
        }
        System.out.println("PASS HierarchyContract checks="+checks+" files="+count+" atoms="+expectedAtoms+" root="+baseline);
    }
}
