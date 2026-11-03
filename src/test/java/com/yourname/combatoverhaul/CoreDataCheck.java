package com.yourname.combatoverhaul;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.combatoverhaul.client.model.BoneSplitter;
import com.yourname.combatoverhaul.client.model.ModelRigJoints;
import com.yourname.combatoverhaul.client.model.ParsedBone;
import com.yourname.combatoverhaul.client.model.ParsedCube;
import com.yourname.combatoverhaul.client.model.ParsedModel;
import com.yourname.combatoverhaul.client.skinned.SkinWeightGenerator;
import com.yourname.combatoverhaul.client.skinned.SkinnedMesh;
import com.yourname.combatoverhaul.rig.RigJoint;
import com.yourname.combatoverhaul.rig.RigJointSet;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线核心数据自检（不启动 Minecraft，不需要 JUnit）。
 *
 * <p>覆盖阶段 1 的关键闭环：
 * <ul>
 *   <li>骨骼未知字段（deform/attach/custom_meta）保存不丢；</li>
 *   <li>缺失 pivot 不会被伪造成显式 0；</li>
 *   <li>重复/超和权重保存时合并、归一化；</li>
 *   <li>NaN、负权重、缺失骨骼引用保存时拒绝。</li>
 * </ul>
 *
 * <p>运行：{@code gradlew coreCheck --offline}
 */
public final class CoreDataCheck {

    private CoreDataCheck() {
    }

    public static void main(String[] args) throws Exception {
        roundTripPreservesBoneAndMeshData();
        missingPivotStaysMissing();
        autoJointsFollowHierarchy();
        splitBoneSegmentsAndSkins();
        splitBoneMultiSegments();
        followSubtreeSync();
        autoSkinSynthetic();
        invalidWeightsAreRejected();
        missingBoneReferenceIsRejected();
        if (args.length > 0 && !args[0].isBlank()) {
            realModelRoundTrip(args[0]);
        }
        System.out.println("CoreDataCheck PASS");
    }

    /** 可选：用真实模型文件做只读 round-trip（不写回原文件）。 */
    private static void realModelRoundTrip(String file) throws Exception {
        JsonObject in = JsonParser.parseString(Files.readString(Path.of(file))).getAsJsonObject();
        ParsedModel model = ParsedModel.parse(in);
        if (model.skinnedMesh == null) {
            nativeModelAutoSkin(model);
            return;
        }
        check(model.skinnedMesh != null, "real model has skinned mesh");
        int vertices = model.skinnedMesh.vertices.size();
        int faces = model.skinnedMesh.faces.size();
        int weights = model.skinnedMesh.weightValues.size();

        JsonObject out = model.toJson();
        check(out.getAsJsonArray("vertices").size() == vertices, "real model vertices preserved");
        check(out.getAsJsonArray("faces").size() == faces, "real model faces preserved");
        check(out.getAsJsonArray("weights").size() == weights, "real model weights preserved");

        JsonObject weapon = findBone(out.getAsJsonObject("roots"), "L_Weapon");
        check(weapon != null, "L_Weapon exists");
        check(weapon.has("deform") && !weapon.get("deform").getAsBoolean(), "L_Weapon deform=false preserved");
        check(weapon.has("attach") && weapon.get("attach").getAsBoolean(), "L_Weapon attach=true preserved");

        RigJointSet joints = ModelRigJoints.from(model);
        check(joints.joints().size() == 23, "real model derives 23 joints");
        check(joints.joint("root") != null && !joints.joint("root").deform(),
                "real model root is non-deforming");
        check(joints.joint("L_Weapon") != null && joints.joint("L_Weapon").attach(),
                "real model weapon attach is not deforming");
        check(!joints.hasLine("root", "Pelvis"), "real model no root -> Pelvis long line");
        check(joints.lines().size() > 0, "real model has drawable bone lines");

        SkinWeightGenerator.Result skin = SkinWeightGenerator.generate(model);
        check(skin.ok, "real model auto skin succeeds: " + skin.error);
        check(skin.vertices == vertices, "real auto skin vertex count");
        check(skin.segments == 20, "real auto skin segment count (23 joints - root - 2 weapons)");
        check(skin.twoInfluence > 0, "real auto skin produces joint blends");
        check(skin.oneInfluence > 0, "real auto skin produces single-bone regions");
        for (int vi = 0; vi < model.skinnedMesh.vertices.size(); vi++) {
            checkWeightSum(model.skinnedMesh, vi);
            checkNoWeight(model.skinnedMesh, vi, "root");
            checkNoWeight(model.skinnedMesh, vi, "L_Weapon");
            checkNoWeight(model.skinnedMesh, vi, "R_Weapon");
            checkInfluenceCount(model.skinnedMesh, vi, 2);
        }
        checkAutoSkinTopology(model.skinnedMesh, joints);

        Map<String, Matrix4f> rest = restMatrices(model);
        checkRestSkinning(model, rest);
        checkBendSkinning(model, rest, "R_Forearm", 0.6f);

        JsonObject skinnedOut = model.toJson();
        check(skinnedOut.has("pivot_space")
                        && "parent_rest".equals(skinnedOut.get("pivot_space").getAsString()),
                "legacy pivot rebase marker written");
        ParsedModel reopened = ParsedModel.parse(skinnedOut);
        check(reopened.skinnedMesh != null, "real auto skinned mesh reparses");
        check(reopened.skinnedMesh.vertices.size() == vertices, "real auto skinned vertices reparse");
        check(reopened.skinnedMesh.faces.size() == faces, "real auto skinned faces reparse");
        check(reopened.skinnedMesh.weightValues.size() == vertices, "real auto skinned weights reparse");
        RigJointSet reopenedJoints = ModelRigJoints.from(reopened);
        check(Math.abs(reopenedJoints.joint("Head").y() - joints.joint("Head").y()) < 1.0e-3f,
                "pivot rebase is idempotent (Head y)");
        check(Math.abs(reopenedJoints.joint("L_Thigh").x() - joints.joint("L_Thigh").x()) < 1.0e-3f,
                "pivot rebase is idempotent (L_Thigh x)");
        System.out.println("RealModelCheck PASS vertices=" + vertices
                + " faces=" + faces + " weights=" + weights
                + " joints=" + joints.joints().size() + " lines=" + joints.lines().size());
        System.out.println("AutoSkinCheck PASS vertices=" + skin.vertices
                + " segments=" + skin.segments
                + " two=" + skin.twoInfluence + " one=" + skin.oneInfluence
                + " sums=" + skin.maxWeightSumError);
    }

    /**
     * 原生 cube 模型（minecraft:player / player_slim）：自动生成连续网格 + 权重。
     * 不依赖 ER 骨名、不依赖自定义模型。
     */
    private static void nativeModelAutoSkin(ParsedModel model) {
        String entity = model.entityId == null ? "<unknown>" : model.entityId;
        check(!model.bones.isEmpty(), "native model has bones: " + entity);

        SkinWeightGenerator.Result skin = SkinWeightGenerator.generate(model);
        check(skin.ok, "native auto skin succeeds: " + skin.error);
        check(skin.meshBuilt, "native cube mesh generated");
        check(skin.meshVertices > 0, "native mesh has vertices");
        check(skin.meshQuads > 0, "native mesh has quads");
        check(skin.vertices == skin.meshVertices, "native weight rows match mesh vertices");
        check(skin.segments > 0, "native model has deform segments");
        check(skin.twoInfluence > 0, "native model has joint blends");
        check(skin.twoInfluence + skin.oneInfluence + skin.noSegment == skin.vertices,
                "native model weight rows fully classified");

        for (int vi = 0; vi < model.skinnedMesh.vertices.size(); vi++) {
            checkWeightSum(model.skinnedMesh, vi);
            checkNoWeight(model.skinnedMesh, vi, "root");
            checkInfluenceCount(model.skinnedMesh, vi, 2);
        }
        RigJointSet joints = ModelRigJoints.from(model);
        checkAutoSkinTopology(model.skinnedMesh, joints);

        Map<String, Matrix4f> rest = restMatrices(model);
        checkRestSkinning(model, rest);
        String target = findSemanticBone(model, "right_arm");
        if (target == null) {
            target = findFirstDeformChild(model);
        }
        check(target != null, "native model has a bendable arm/limb");
        checkBendGeneric(model, rest, target, 0.6f);

        JsonObject out = model.toJson();
        check(out.getAsJsonArray("vertices").size() == skin.meshVertices,
                "native vertices written");
        check(out.getAsJsonArray("faces").size() == skin.meshQuads,
                "native faces written");
        check(out.getAsJsonArray("weights").size() == skin.meshVertices,
                "native weights written");
        check(out.has("source_bones")
                        && out.getAsJsonArray("source_bones").size() == skin.meshVertices,
                "native source cube bones written");
        ParsedModel reopened = ParsedModel.parse(out);
        check(reopened.skinnedMesh != null, "native auto skinned mesh reparses");
        check(reopened.skinnedMesh.vertices.size() == skin.meshVertices,
                "native auto skinned vertices reparse");
        check(reopened.skinnedMesh.faces.size() == skin.meshQuads,
                "native auto skinned faces reparse");
        check(reopened.skinnedMesh.weightValues.size() == skin.meshVertices,
                "native auto skinned weights reparse");
        check(reopened.skinnedMesh.sourceBones.size() == skin.meshVertices,
                "native source cube bones reparse");

        nativeOuterFollow(out);

        String body = findSemanticBone(model, "body");
        String arm = findSemanticBone(model, "right_arm");
        boolean shoulderBlend = body != null && arm != null
                && hasPair(model.skinnedMesh, body, arm);
        check(shoulderBlend, "native shoulder body/arm blend exists");

        // 手动刷选种子：把 body 顶点强制绑定到 body，验证优先级和持久化。
        if (body != null) {
            var mesh = model.skinnedMesh;
            while (mesh.bindBones.size() < mesh.vertices.size()) {
                mesh.bindBones.add("");
            }
            int manualVi = findSourceVertex(mesh, body, 7.0f);
            check(manualVi >= 0, "found a body source vertex for manual bind");
            mesh.bindBones.set(manualVi, body);
            SkinWeightGenerator.Result manualSkin = SkinWeightGenerator.generate(model);
            check(manualSkin.ok, "manual bind auto skin succeeds: " + manualSkin.error);
            checkWeightAtLeast(mesh, manualVi, body, 0.99f);

            JsonObject boundOut = model.toJson();
            check(boundOut.has("bind_bones")
                            && boundOut.getAsJsonArray("bind_bones").size() == mesh.vertices.size(),
                    "manual bind_bones written");
            ParsedModel boundReopened = ParsedModel.parse(boundOut);
            check(boundReopened.skinnedMesh != null
                            && boundReopened.skinnedMesh.bindBones.size() == mesh.vertices.size()
                            && body.equals(boundReopened.skinnedMesh.bindBones.get(manualVi)),
                    "manual bind_bones persists after save/reload");
            System.out.println("ManualBindCheck PASS entity=" + entity
                    + " vertex=" + manualVi + " bone=" + body);
        }

        // 原生模型上实际执行一次“分段关节 → 重建网格 → 自动蒙皮”。
        if (arm != null) {
            ParsedBone armBone = model.findBone(arm);
            BoneSplitter.Result split = BoneSplitter.splitAtMid(model, armBone);
            check(split.ok, "native split joint succeeds: " + split.error);
            ParsedBone splitChild = model.findBone(split.childName);
            check(splitChild != null, "native split child exists");
            model.skinnedMesh = null;
            SkinWeightGenerator.Result splitSkin = SkinWeightGenerator.generate(model);
            check(splitSkin.ok, "native split auto skin succeeds: " + splitSkin.error);
            check(splitSkin.meshBuilt, "native split rebuilds mesh");
            check(splitSkin.segments >= skin.segments + 1, "native split adds a bone segment");
            check(hasBoneWeight(model.skinnedMesh, splitChild.name),
                    "native split child receives weights");
            check(!split.outerChildNames.isEmpty(), "outer sleeve split child created");
            if (!split.outerChildNames.isEmpty()) {
                ParsedBone outerChild = model.findBone(split.outerChildNames.get(0));
                check(outerChild != null && outerChild.parent == splitChild,
                        "outer sleeve segment parented to split child");
                int sleeveVi = findSourceVertex(model.skinnedMesh, outerChild.name, 8.0f);
                check(sleeveVi >= 0, "found lower sleeve source vertex");
                checkWeightAtLeast(model.skinnedMesh, sleeveVi, outerChild.name, 0.5f);
            }
            for (int vi = 0; vi < model.skinnedMesh.vertices.size(); vi++) {
                checkWeightSum(model.skinnedMesh, vi);
                checkInfluenceCount(model.skinnedMesh, vi, 2);
            }
            checkRestSkinning(model, restMatrices(model));
            checkBendGeneric(model, restMatrices(model), splitChild.name, 0.5f);
            JsonObject splitOut = model.toJson();
            ParsedModel splitReopened = ParsedModel.parse(splitOut);
            check(splitReopened.findBone(splitChild.name) != null,
                    "native split child persists after save/reload");
            check(splitReopened.skinnedMesh != null
                            && splitReopened.skinnedMesh.weightValues.size() == splitSkin.meshVertices,
                    "native split weights persist after save/reload");
            System.out.println("SplitNativeCheck PASS entity=" + entity
                    + " bone=" + arm + " -> " + splitChild.name
                    + " vertices=" + splitSkin.meshVertices
                    + " segments=" + splitSkin.segments);
        }
        System.out.println("NativeModelAutoSkin PASS entity=" + entity
                + " cubes=" + skin.meshQuads + " vertices=" + skin.meshVertices
                + " segments=" + skin.segments
                + " two=" + skin.twoInfluence + " one=" + skin.oneInfluence
                + " shoulderBlend=" + shoulderBlend);
    }

    private static void nativeOuterFollow(JsonObject sourceJson) {
        ParsedModel fm = ParsedModel.parse(sourceJson.deepCopy());
        ParsedBone arm = fm.findBone(findSemanticBone(fm, "right_arm"));
        ParsedBone sleeve = fm.findBone(findSemanticBone(fm, "right_sleeve"));
        if (arm == null || sleeve == null) {
            return;
        }
        if (sleeve.parent != null) {
            sleeve.parent.children.remove(sleeve);
        } else {
            fm.roots.remove(sleeve);
        }
        sleeve.parent = arm.parent;
        if (arm.parent != null) {
            arm.parent.children.add(sleeve);
        } else {
            fm.roots.add(sleeve);
        }
        sleeve.pivotX = arm.pivotX;
        sleeve.pivotY = arm.pivotY;
        sleeve.pivotZ = arm.pivotZ;
        sleeve.rotX = arm.rotX;
        sleeve.rotY = arm.rotY;
        sleeve.rotZ = arm.rotZ;
        sleeve.scaleX = arm.scaleX;
        sleeve.scaleY = arm.scaleY;
        sleeve.scaleZ = arm.scaleZ;
        sleeve.follow = arm.name;
        sleeve.hasFollow = true;
        fm.flatten();
        fm.syncFollow();

        BoneSplitter.Result split = BoneSplitter.split(fm, arm, 1, new float[]{0.5f, 0.5f});
        check(split.ok, "native outer follow split succeeds: " + split.error);
        ParsedBone armChild = fm.findBone(split.childName);
        check(armChild != null, "native outer follow target child exists");
        ParsedBone sleeveChild = null;
        for (ParsedBone c : sleeve.children) {
            if (c != null && Math.abs(c.pivotY - armChild.pivotY) < 1.0e-3f) {
                sleeveChild = c;
                break;
            }
        }
        check(sleeveChild != null, "native outer follow child created");
        check(sleeveChild.parent == sleeve, "native outer follow child parent is sleeve");
        check(Math.abs(sleeveChild.pivotY - armChild.pivotY) < 1.0e-3f,
                "native outer follow child pivot matches target");
        check(armChild.name.equals(sleeveChild.follow),
                "native outer follow child explicit target");
        fm.skinnedMesh = null;
        fm.syncFollow();
        SkinWeightGenerator.Result followSkin = SkinWeightGenerator.generate(fm);
        check(followSkin.ok, "native outer follow auto skin succeeds: " + followSkin.error);
        int armChildVertex = findSourceVertex(fm.skinnedMesh, armChild.name, 8.0f);
        int sleeveChildVertex = findSourceVertex(fm.skinnedMesh, sleeveChild.name, 8.0f);
        check(armChildVertex >= 0, "inner split vertex exists");
        check(sleeveChildVertex >= 0, "outer split vertex exists");
        checkWeightAtLeast(fm.skinnedMesh, armChildVertex, armChild.name, 0.5f);
        checkWeightAnyAtLeast(fm.skinnedMesh, sleeveChildVertex, 0.5f,
                sleeveChild.name, armChild.name);
        for (int vi = 0; vi < fm.skinnedMesh.vertices.size(); vi++) {
            checkWeightSum(fm.skinnedMesh, vi);
            checkNoWeight(fm.skinnedMesh, vi, "root");
        }
        float[] sleeveWorld = fm.boneWorldPos(sleeve.name);
        float[] armWorld = fm.boneWorldPos(arm.name);
        check(dist3(sleeveWorld, armWorld) < 1.0e-4f,
                "native outer follow root world equals target");
        float[] sleeveChildWorld = fm.boneWorldPos(sleeveChild.name);
        float[] armChildWorld = fm.boneWorldPos(armChild.name);
        check(dist3(sleeveChildWorld, armChildWorld) < 1.0e-4f,
                "native outer follow child world equals target child");
        System.out.println("FollowNativeCheck PASS sleeve=" + sleeve.name
                + " child=" + sleeveChild.name + " target=" + armChild.name);
    }

    private static String findSemanticBone(ParsedModel model, String semantic) {
        for (ParsedBone bone : model.bones) {
            if (semantic.equals(model.semanticName(bone.name))) {
                return bone.name;
            }
        }
        return null;
    }

    private static String findFirstDeformChild(ParsedModel model) {
        for (ParsedBone bone : model.bones) {
            if (bone.parent != null && (bone.hasDeform ? bone.deform : true)
                    && !(bone.hasAttach && bone.attach)) {
                return bone.name;
            }
        }
        return null;
    }

    private static void checkBendGeneric(ParsedModel model, Map<String, Matrix4f> rest,
                                         String target, float angleRad) {
        SkinnedMesh mesh = model.skinnedMesh;
        java.util.Set<String> movingBones = subtree(model, target);
        int moving = findMaxWeight(mesh, movingBones);
        int still = findMaxNoWeight(mesh, movingBones);
        check(moving >= 0, "found a vertex in target subtree");
        check(still >= 0, "found an unrelated vertex");

        Map<String, Matrix4f> bent = bentMatrices(model, target, angleRad);
        float moved = skinVertex(mesh, moving, rest, rest)
                .distance(skinVertex(mesh, moving, rest, bent));
        float stillMoved = skinVertex(mesh, still, rest, rest)
                .distance(skinVertex(mesh, still, rest, bent));
        check(moved > 0.05f, "native bend moved target vertex: " + moved);
        check(stillMoved < 1.0e-4f, "native unrelated vertex moved: " + stillMoved);
    }

    private static java.util.Set<String> subtree(ParsedModel model, String rootName) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        ParsedBone root = model.findBone(rootName);
        collectSubtree(root, out);
        return out;
    }

    private static void collectSubtree(ParsedBone bone, java.util.Set<String> out) {
        if (bone == null) {
            return;
        }
        out.add(bone.name);
        for (ParsedBone child : bone.children) {
            collectSubtree(child, out);
        }
    }

    private static boolean hasBoneWeight(SkinnedMesh mesh, String bone) {
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            String[] names = mesh.weightBones.get(vi);
            float[] vals = mesh.weightValues.get(vi);
            for (int k = 0; k < names.length; k++) {
                if (vals[k] > 1.0e-5f && bone.equals(names[k])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasPair(SkinnedMesh mesh, String a, String b) {
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            String[] names = mesh.weightBones.get(vi);
            float[] vals = mesh.weightValues.get(vi);
            boolean wa = false;
            boolean wb = false;
            for (int k = 0; k < names.length; k++) {
                if (vals[k] <= 1.0e-5f) {
                    continue;
                }
                if (a.equals(names[k])) {
                    wa = true;
                }
                if (b.equals(names[k])) {
                    wb = true;
                }
            }
            if (wa && wb) {
                return true;
            }
        }
        return false;
    }

    private static int findMaxNoWeight(SkinnedMesh mesh, java.util.Set<String> bones) {
        int best = -1;
        float bestWeight = Float.MAX_VALUE;
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            String[] names = mesh.weightBones.get(vi);
            float[] vals = mesh.weightValues.get(vi);
            float sum = 0f;
            for (int k = 0; k < names.length; k++) {
                if (names[k] != null && bones.contains(names[k])) {
                    sum += vals[k];
                }
            }
            if (sum < bestWeight) {
                bestWeight = sum;
                best = vi;
            }
        }
        return bestWeight <= 1.0e-6f ? best : -1;
    }

    private static JsonObject findBone(JsonObject roots, String name) {
        for (Map.Entry<String, JsonElement> e : roots.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject node = e.getValue().getAsJsonObject();
            if (name.equals(e.getKey())
                    || (node.has("name") && node.get("name").isJsonPrimitive()
                    && name.equals(node.get("name").getAsString()))) {
                return node;
            }
            if (node.has("children") && node.get("children").isJsonObject()) {
                JsonObject found = findBone(node.getAsJsonObject("children"), name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void roundTripPreservesBoneAndMeshData() {
        String json = """
                {
                  "format": 1,
                  "entity": "combatoverhaul:core_check",
                  "roots": {
                    "root": {
                      "name": "root",
                      "pivot": [0, 0, 0],
                      "children": {
                        "child": {
                          "name": "child",
                          "pivot": [0, 1, 0],
                          "deform": false,
                          "attach": true,
                          "rest_matrix": [1,0,0,0,1,0,0,0,1],
                          "custom_meta": {"tag": "keep-me"},
                          "cubes": []
                        }
                      }
                    }
                  },
                  "vertices": [[0,0,0,0,0],[1,0,0,1,0],[1,1,0,1,1]],
                  "faces": [[0,1,2]],
                  "weights": [
                    [{"bone":"child","w":0.5},{"bone":"child","w":1.5}],
                    [{"bone":"root","w":1.0}],
                    [{"bone":"child","w":0.25},{"bone":"root","w":0.75}]
                  ]
                }
                """;
        ParsedModel model = ParsedModel.parse(JsonParser.parseString(json).getAsJsonObject());
        check(model.skinnedMesh != null, "mesh parsed");
        check(model.findBone("child") != null, "child bone exists");

        JsonObject out = model.toJson();
        JsonObject child = out.getAsJsonObject("roots").getAsJsonObject("root")
                .getAsJsonObject("children").getAsJsonObject("child");
        check(child.has("deform") && !child.get("deform").getAsBoolean(), "deform=false preserved");
        check(child.has("attach") && child.get("attach").getAsBoolean(), "attach=true preserved");
        check(child.has("custom_meta")
                        && child.getAsJsonObject("custom_meta").get("tag").getAsString().equals("keep-me"),
                "unknown bone field preserved");
        check(child.has("rest_matrix") && child.getAsJsonArray("rest_matrix").size() == 9,
                "rest_matrix preserved as 9 floats");
        check(!child.has("cubes"), "empty cubes not written as stale data");

        JsonArray weights = out.getAsJsonArray("weights");
        check(weights.size() == 3, "one weight row per vertex");
        JsonArray first = weights.get(0).getAsJsonArray();
        check(first.size() == 1, "duplicate bone merged");
        check(first.get(0).getAsJsonObject().get("bone").getAsString().equals("child"),
                "merged bone name");
        check(Math.abs(first.get(0).getAsJsonObject().get("w").getAsFloat() - 1.0f) < 1.0e-5f,
                "weight normalized");
        check(out.has("vertices") && out.getAsJsonArray("vertices").size() == 3, "vertices written");
        check(out.has("faces") && out.getAsJsonArray("faces").size() == 1, "faces written");

        // 写出的 JSON 必须能重新打开，而不是只在内存里正确。
        ParsedModel reopened = ParsedModel.parse(JsonParser.parseString(out.toString()).getAsJsonObject());
        check(reopened.skinnedMesh != null, "saved mesh reparses");
        check(reopened.skinnedMesh.vertices.size() == 3, "saved vertices reparse");
        check(reopened.skinnedMesh.faces.size() == 1, "saved faces reparse");
        check(reopened.findBone("child") != null
                        && reopened.findBone("child").hasDeform
                        && !reopened.findBone("child").deform,
                "deform survives reparse");
    }

    private static void missingPivotStaysMissing() {
        String json = """
                {
                  "roots": {
                    "root": {
                      "name": "root",
                      "children": {
                        "no_pivot": {"name": "no_pivot", "custom_flag": true}
                      }
                    }
                  }
                }
                """;
        ParsedModel model = ParsedModel.parse(JsonParser.parseString(json).getAsJsonObject());
        JsonObject out = model.toJson();
        JsonObject noPivot = out.getAsJsonObject("roots").getAsJsonObject("root")
                .getAsJsonObject("children").getAsJsonObject("no_pivot");
        check(!noPivot.has("pivot"), "missing pivot must stay missing");
        check(noPivot.has("custom_flag") && noPivot.get("custom_flag").getAsBoolean(),
                "unknown field on pivot-less bone preserved");
    }

    private static void autoJointsFollowHierarchy() {
        String json = """
                {
                  "roots": {
                    "root": {
                      "name": "root",
                      "pivot": [0, 0, 0],
                      "children": {
                        "child": {
                          "name": "child",
                          "pivot": [0, 4, 0],
                          "children": {
                            "grand": {"name": "grand", "pivot": [0, 4, 0]},
                            "zero": {"name": "zero", "pivot": [0, 0, 0]},
                            "weapon": {
                              "name": "weapon",
                              "pivot": [1, 0, 0],
                              "deform": false,
                              "attach": true
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;
        ParsedModel model = ParsedModel.parse(JsonParser.parseString(json).getAsJsonObject());
        RigJointSet set = ModelRigJoints.from(model);
        check(set.joints().size() == 5, "all existing bones become joints");
        check(set.joint("root") != null && !set.joint("root").deform(),
                "root defaults to non-deforming");
        check(set.joint("child") != null && set.joint("child").deform(),
                "normal child defaults to deforming");
        check(set.joint("weapon") != null && set.joint("weapon").attach()
                        && !set.joint("weapon").deform(),
                "weapon attach flags preserved");
        check(set.joint("grand") != null && Math.abs(set.joint("grand").y() - 8f) < 1e-5f,
                "joint world position accumulates parent pivots");

        check(!set.hasLine("root", "child"), "root -> child long line excluded");
        check(set.hasLine("child", "grand"), "normal parent -> child line included");
        check(!set.hasLine("child", "zero"), "zero-length line excluded");
        check(!set.hasLine("child", "weapon"), "attach bone line excluded");
        check(set.lines().size() == 1, "only one drawable bone line");
    }

    private static void autoSkinSynthetic() {
        String json = """
                {
                  "roots": {
                    "root": {
                      "name": "root",
                      "pivot": [0, 0, 0],
                      "children": {
                        "upper": {
                          "name": "upper",
                          "pivot": [0, -4, 0],
                          "children": {
                            "lower": {
                              "name": "lower",
                              "pivot": [0, -4, 0],
                              "children": {
                                "weapon": {
                                  "name": "weapon",
                                  "pivot": [1, 0, 0],
                                  "deform": false,
                                  "attach": true
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  },
                  "vertices": [
                    [0,-2,0,0,0],
                    [0,-4,0,0,0],
                    [0,-6,0,0,0],
                    [0,-10,0,0,0]
                  ],
                  "faces": [[0,1,2],[1,2,3]]
                }
                """;
        ParsedModel model = ParsedModel.parse(JsonParser.parseString(json).getAsJsonObject());
        SkinWeightGenerator.Result r = SkinWeightGenerator.generate(model);
        check(r.ok, "synthetic auto skin succeeds");
        check(r.vertices == 4, "synthetic vertex count");
        check(r.segments == 2, "synthetic deform segments");
        check(r.twoInfluence == 1, "joint vertex gets two influences");
        check(r.oneInfluence == 3, "middle/end vertices get one influence");

        SkinnedMesh mesh = model.skinnedMesh;
        checkWeight(mesh, 0, "upper", 1.0f);
        checkWeight(mesh, 1, "upper", 0.5f);
        checkWeight(mesh, 1, "lower", 0.5f);
        checkWeight(mesh, 2, "lower", 1.0f);
        checkWeight(mesh, 3, "lower", 1.0f);
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            checkNoWeight(mesh, vi, "root");
            checkNoWeight(mesh, vi, "weapon");
            checkWeightSum(mesh, vi);
        }

        JsonObject out = model.toJson();
        ParsedModel reopened = ParsedModel.parse(out);
        check(reopened.skinnedMesh != null, "auto skinned mesh reparses");
        checkWeight(reopened.skinnedMesh, 1, "upper", 0.5f);
        checkWeight(reopened.skinnedMesh, 1, "lower", 0.5f);
    }

    private static void splitBoneSegmentsAndSkins() {
        ParsedModel model = ParsedModel.emptyDefault("test:split");
        ParsedBone root = model.roots.get(0);
        root.name = "arm";
        root.pivotX = 5f;
        root.pivotY = 2f;
        root.pivotZ = 0f;
        ParsedCube cube = new ParsedCube(-3f, -2f, -2f, 4f, 12f, 4f);
        cube.fillFacesFromTexel(0f, 0f, 0.25f, 0.75f);
        root.cubes.add(cube);
        model.flatten();

        BoneSplitter.Result split = BoneSplitter.splitAtMid(model, root);
        check(split.ok, "split bone succeeds: " + split.error);
        check(split.axis == 1, "split picks longest Y axis");
        check(Math.abs(split.splitPosition - 4f) < 1.0e-5f, "split at cube midpoint");
        check(root.cubes.size() == 1, "upper segment keeps one cube");
        check(root.children.size() == 1, "child segment created");
        ParsedBone child = model.findBone(split.childName);
        check(child != null, "child bone exists");
        check(child.cubes.size() == 1, "lower segment has one cube");
        check(Math.abs(child.pivotY - 4f) < 1.0e-5f, "child pivot at split plane");
        check(Math.abs((root.pivotY + root.cubes.get(0).originY + root.cubes.get(0).sizeY)
                - 6f) < 1.0e-4f, "upper segment world end at split");
        check(Math.abs((root.pivotY + child.pivotY + child.cubes.get(0).originY) - 6f) < 1.0e-4f,
                "lower segment world start at split");
        check(Math.abs(child.cubes.get(0).sizeY - 6f) < 1.0e-4f, "lower segment length");

        SkinWeightGenerator.Result skin = SkinWeightGenerator.generate(model);
        check(skin.ok, "split then auto skin succeeds: " + skin.error);
        check(skin.meshBuilt, "split rebuilds mesh from cubes");
        check(skin.vertices > 0, "split mesh has vertices");
        check(skin.twoInfluence > 0, "split seam has soft blend: two=" + skin.twoInfluence
                + " one=" + skin.oneInfluence + " verts=" + skin.vertices);
        check(skin.twoInfluence < skin.vertices / 2, "split soft blend limited to seam");
        System.out.println("SplitSeamCheck two=" + skin.twoInfluence + " one=" + skin.oneInfluence);
        for (int vi = 0; vi < model.skinnedMesh.vertices.size(); vi++) {
            checkWeightSum(model.skinnedMesh, vi);
            checkInfluenceCount(model.skinnedMesh, vi, 2);
        }
        checkRestSkinning(model, restMatrices(model));
        System.out.println("SplitJointCheck PASS child=" + child.name
                + " vertices=" + skin.vertices + " segments=" + skin.segments);
    }

    private static void splitBoneMultiSegments() {
        ParsedModel model = ParsedModel.emptyDefault("test:split3");
        ParsedBone root = model.roots.get(0);
        root.name = "arm";
        ParsedCube cube = new ParsedCube(-3f, -2f, -2f, 4f, 12f, 4f);
        cube.fillFacesFromTexel(0f, 0f, 0.25f, 0.75f);
        root.cubes.add(cube);
        model.flatten();

        BoneSplitter.Result split = BoneSplitter.split(model, root, 1,
                new float[]{0.2f, 0.3f, 0.5f});
        check(split.ok, "multi split succeeds: " + split.error);
        check(split.segments == 3, "multi split segment count");
        check(split.childNames.size() == 2, "multi split child chain");
        check(Math.abs(split.boundaries[0] - 0.4f) < 1.0e-4f, "multi split boundary 1");
        check(Math.abs(split.boundaries[1] - 4.0f) < 1.0e-4f, "multi split boundary 2");
        check(Math.abs(root.cubes.get(0).sizeY - 2.4f) < 1.0e-4f, "multi split segment 0 size");
        ParsedBone child1 = model.findBone(split.childNames.get(0));
        ParsedBone child2 = model.findBone(split.childNames.get(1));
        check(child1 != null && child2 != null, "multi split child bones exist");
        check(child1.parent == root, "child1 parent is original");
        check(child2.parent == child1, "child2 parent is child1");
        check(Math.abs(child1.pivotY - 0.4f) < 1.0e-4f, "child1 pivot at boundary");
        check(Math.abs(child2.pivotY - 3.6f) < 1.0e-4f, "child2 relative pivot");
        check(Math.abs(child1.cubes.get(0).originY) < 1.0e-4f
                        && Math.abs(child1.cubes.get(0).sizeY - 3.6f) < 1.0e-4f,
                "child1 cube rebased");
        check(Math.abs(child2.cubes.get(0).originY) < 1.0e-4f
                        && Math.abs(child2.cubes.get(0).sizeY - 6.0f) < 1.0e-4f,
                "child2 cube rebased");

        SkinWeightGenerator.Result skin = SkinWeightGenerator.generate(model);
        check(skin.ok, "multi split auto skin succeeds: " + skin.error);
        for (int vi = 0; vi < model.skinnedMesh.vertices.size(); vi++) {
            checkWeightSum(model.skinnedMesh, vi);
            checkInfluenceCount(model.skinnedMesh, vi, 2);
        }
        checkRestSkinning(model, restMatrices(model));
        System.out.println("SplitMultiCheck PASS segments=" + split.segments
                + " vertices=" + skin.vertices + " influences=" + skin.segments);
    }

    private static void followSubtreeSync() {
        ParsedModel model = ParsedModel.emptyDefault("test:follow");
        ParsedBone body = model.roots.get(0);
        body.name = "body";
        ParsedBone arm = new ParsedBone("arm");
        ParsedBone sleeve = new ParsedBone("sleeve");
        arm.parent = body;
        sleeve.parent = body;
        arm.hasPivot = sleeve.hasPivot = true;
        arm.pivotY = 2f;
        sleeve.pivotY = 2f;
        arm.cubes.add(new ParsedCube(-1f, -2f, -1f, 2f, 12f, 2f));
        sleeve.cubes.add(new ParsedCube(-1f, -2f, -1f, 2f, 12f, 2f));
        body.children.add(arm);
        body.children.add(sleeve);
        sleeve.follow = arm.name;
        sleeve.hasFollow = true;
        model.flatten();
        model.syncFollow();
        arm.animRotY = 0.4f;
        model.syncFollow();
        check(Math.abs(sleeve.animRotY - 0.4f) < 1.0e-5f,
                "follow root copies target animation");

        BoneSplitter.Result split = BoneSplitter.split(model, arm, 1, new float[]{0.5f, 0.5f});
        check(split.ok, "follow target split succeeds: " + split.error);
        check(split.childNames.size() == 1, "follow target child created");
        ParsedBone armChild = model.findBone(split.childNames.get(0));
        check(armChild != null, "follow target child exists");
        ParsedBone sleeveChild = null;
        for (ParsedBone c : sleeve.children) {
            if (c != null && Math.abs(c.pivotY - armChild.pivotY) < 1.0e-4f) {
                sleeveChild = c;
                break;
            }
        }
        check(sleeveChild != null, "follow subtree split child created");
        check(Math.abs(sleeveChild.pivotY - armChild.pivotY) < 1.0e-4f,
                "follow child copies target child pivot");
        check(armChild.name.equals(sleeveChild.follow),
                "follow child has explicit target mapping");
        check(sleeve.follow != null && sleeve.follow.equals(arm.name),
                "follow link persists after split");
        ParsedModel reopened = ParsedModel.parse(model.toJson());
        ParsedBone reopenedSleeve = reopened.findBone("sleeve");
        check(reopenedSleeve != null && arm.name.equals(reopenedSleeve.follow),
                "follow link saves and reparses");
        ParsedBone reopenedSleeveChild = reopened.findBone(sleeveChild.name);
        check(reopenedSleeveChild != null && armChild.name.equals(reopenedSleeveChild.follow),
                "follow child explicit mapping saves and reparses");
        System.out.println("FollowCheck PASS root=" + sleeve.name
                + " child=" + sleeveChild.name + " target=" + armChild.name);
    }

    private static void invalidWeightsAreRejected() {
        ParsedModel model = meshOnlyModel();
        model.skinnedMesh.weightValues.get(0)[0] = Float.NaN;
        expectFailure(model, "NaN weight");
    }

    private static void missingBoneReferenceIsRejected() {
        ParsedModel model = meshOnlyModel();
        model.skinnedMesh.weightBones.get(0)[0] = "does_not_exist";
        model.skinnedMesh.weightValues.get(0)[0] = 1.0f;
        expectFailure(model, "missing bone reference");
    }

    private static ParsedModel meshOnlyModel() {
        String json = """
                {
                  "roots": {
                    "root": {
                      "name": "root",
                      "pivot": [0, 0, 0],
                      "children": {
                        "child": {"name": "child", "pivot": [0, 1, 0]}
                      }
                    }
                  },
                  "vertices": [[0,0,0,0,0],[1,0,0,1,0],[1,1,0,1,1]],
                  "faces": [[0,1,2]],
                  "weights": [
                    [{"bone":"child","w":1.0}],
                    [{"bone":"root","w":1.0}],
                    [{"bone":"child","w":1.0}]
                  ]
                }
                """;
        ParsedModel model = ParsedModel.parse(JsonParser.parseString(json).getAsJsonObject());
        check(model.skinnedMesh != null, "mesh-only model parsed");
        return model;
    }

    private static float dist3(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int findSourceVertex(SkinnedMesh mesh, String sourceBone, float targetY) {
        if (mesh.sourceBones.size() != mesh.vertices.size()) {
            return -1;
        }
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            if (!sourceBone.equals(mesh.sourceBones.get(vi))) {
                continue;
            }
            float d = Math.abs(mesh.vertices.get(vi).y - targetY);
            if (d < bestD) {
                bestD = d;
                best = vi;
            }
        }
        return best;
    }

    private static void checkWeightAnyAtLeast(SkinnedMesh mesh, int vi, float min, String... bones) {
        String[] names = mesh.weightBones.get(vi);
        float[] vals = mesh.weightValues.get(vi);
        for (String bone : bones) {
            for (int i = 0; i < names.length; i++) {
                if (bone.equals(names[i]) && vals[i] >= min) {
                    return;
                }
            }
        }
        throw new AssertionError("vertex " + vi + " has no weight >= " + min
                + " for any of " + java.util.Arrays.toString(bones));
    }

    private static void checkWeightAtLeast(SkinnedMesh mesh, int vi, String bone, float min) {
        String[] names = mesh.weightBones.get(vi);
        float[] vals = mesh.weightValues.get(vi);
        for (int i = 0; i < names.length; i++) {
            if (bone.equals(names[i])) {
                check(vals[i] >= min, "vertex " + vi + " weight " + bone
                        + " expected >= " + min + " got " + vals[i]);
                return;
            }
        }
        throw new AssertionError("vertex " + vi + " has no weight for " + bone);
    }

    private static void checkWeight(SkinnedMesh mesh, int vi, String bone, float expected) {
        String[] names = mesh.weightBones.get(vi);
        float[] vals = mesh.weightValues.get(vi);
        for (int i = 0; i < names.length; i++) {
            if (bone.equals(names[i])) {
                check(Math.abs(vals[i] - expected) < 1.0e-4f,
                        "vertex " + vi + " weight " + bone + " expected " + expected + " got " + vals[i]);
                return;
            }
        }
        throw new AssertionError("vertex " + vi + " has no weight for " + bone);
    }

    private static void checkNoWeight(SkinnedMesh mesh, int vi, String bone) {
        String[] names = mesh.weightBones.get(vi);
        for (String name : names) {
            check(!bone.equals(name), "vertex " + vi + " must not be weighted to " + bone);
        }
    }

    private static void checkWeightSum(SkinnedMesh mesh, int vi) {
        float sum = 0f;
        for (float w : mesh.weightValues.get(vi)) {
            sum += w;
        }
        check(Math.abs(sum - 1f) < 1.0e-4f, "vertex " + vi + " weight sum " + sum);
    }

    private static void checkInfluenceCount(SkinnedMesh mesh, int vi, int max) {
        int count = 0;
        for (float w : mesh.weightValues.get(vi)) {
            if (w > 1.0e-5f) {
                count++;
            }
        }
        check(count <= max, "vertex " + vi + " influences " + count + " > " + max);
    }

    private static Map<String, Matrix4f> restMatrices(ParsedModel model) {
        Map<String, Matrix4f> out = new HashMap<>();
        for (ParsedBone root : model.roots) {
            buildRest(root, new Matrix4f(), out);
        }
        return out;
    }

    private static void buildRest(ParsedBone bone, Matrix4f parent, Map<String, Matrix4f> out) {
        Matrix4f local = new Matrix4f(parent)
                .translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        Matrix4f restRot = restRotation(bone);
        if (restRot != null) {
            local.mul(restRot);
        } else {
            local.rotateZYX(bone.rotZ, bone.rotY, bone.rotX);
        }
        local.scale(bone.scaleX, bone.scaleY, bone.scaleZ);
        out.put(bone.name, local);
        for (ParsedBone child : bone.children) {
            buildRest(child, local, out);
        }
    }

    /**
     * 自动蒙皮拓扑检查：
     * 1) 双影响的两根骨骼必须父/子相邻，不能跨肢体；
     * 2) 明显在左侧/右侧的顶点不能高权重绑定到对侧肢体。
     */
    private static void checkAutoSkinTopology(SkinnedMesh mesh, RigJointSet joints) {
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            String[] names = mesh.weightBones.get(vi);
            float[] vals = mesh.weightValues.get(vi);
            int first = -1;
            int second = -1;
            for (int k = 0; k < names.length; k++) {
                if (vals[k] <= 1.0e-5f) {
                    continue;
                }
                if (first < 0) {
                    first = k;
                } else if (second < 0) {
                    second = k;
                } else {
                    throw new AssertionError("vertex " + vi + " has more than 2 influences");
                }
            }
            if (first >= 0 && second >= 0) {
                RigJoint a = joints.joint(names[first]);
                RigJoint b = joints.joint(names[second]);
                check(a != null && b != null, "two-influence bones must exist");
                check(names[first].equals(b.parent()) || names[second].equals(a.parent()),
                        "two-influence bones must be parent/child: "
                                + names[first] + " / " + names[second]);
            }

            SkinnedMesh.Vertex v = mesh.vertices.get(vi);
            String opposite = v.x > 3.0f ? "R_" : v.x < -3.0f ? "L_" : null;
            if (opposite != null) {
                for (int k = 0; k < names.length; k++) {
                    if (names[k] != null && names[k].startsWith(opposite) && vals[k] > 0.25f) {
                        throw new AssertionError("vertex " + vi + " x=" + v.x
                                + " is cross-bound to " + names[k] + " w=" + vals[k]);
                    }
                }
            }
        }
    }

    private static void checkRestSkinning(ParsedModel model, Map<String, Matrix4f> rest) {
        SkinnedMesh mesh = model.skinnedMesh;
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            SkinnedMesh.Vertex v = mesh.vertices.get(vi);
            Vector3f p = skinVertex(mesh, vi, rest, rest);
            float dx = p.x - v.x;
            float dy = p.y - v.y;
            float dz = p.z - v.z;
            check(dx * dx + dy * dy + dz * dz < 1.0e-5f,
                    "rest skinning moved vertex " + vi + " by " + Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
    }

    private static void checkBendSkinning(ParsedModel model, Map<String, Matrix4f> rest,
                                          String target, float angleRad) {
        SkinnedMesh mesh = model.skinnedMesh;
        Map<String, Matrix4f> bent = bentMatrices(model, target, angleRad);
        int moving = findMaxWeight(mesh, Set.of("R_Hand", "R_Forearm"));
        int still = findMaxWeight(mesh, Set.of("Spine", "Spine1", "Spine2"));
        check(moving >= 0, "found a vertex weighted to the bent arm");
        check(still >= 0, "found a torso vertex that must stay still");

        Vector3f movingRest = skinVertex(mesh, moving, rest, rest);
        Vector3f movingBent = skinVertex(mesh, moving, rest, bent);
        float moved = movingRest.distance(movingBent);
        check(moved > 0.05f, "bent arm vertex did not move: " + moved);

        Vector3f stillRest = skinVertex(mesh, still, rest, rest);
        Vector3f stillBent = skinVertex(mesh, still, rest, bent);
        float stillMoved = stillRest.distance(stillBent);
        check(stillMoved < 1.0e-4f, "torso vertex moved with arm: " + stillMoved);
    }

    private static int findMaxWeight(SkinnedMesh mesh, Set<String> bones) {
        int best = -1;
        float bestWeight = 0f;
        for (int vi = 0; vi < mesh.vertices.size(); vi++) {
            String[] names = mesh.weightBones.get(vi);
            float[] vals = mesh.weightValues.get(vi);
            float sum = 0f;
            for (int k = 0; k < names.length; k++) {
                if (names[k] != null && bones.contains(names[k])) {
                    sum += vals[k];
                }
            }
            if (sum > bestWeight) {
                bestWeight = sum;
                best = vi;
            }
        }
        return best;
    }

    private static Vector3f skinVertex(SkinnedMesh mesh, int vi,
                                       Map<String, Matrix4f> rest,
                                       Map<String, Matrix4f> animated) {
        SkinnedMesh.Vertex v = mesh.vertices.get(vi);
        String[] names = mesh.weightBones.get(vi);
        float[] vals = mesh.weightValues.get(vi);
        Vector3f out = new Vector3f();
        for (int k = 0; k < names.length; k++) {
            float w = vals[k];
            if (w <= 0f) {
                continue;
            }
            Matrix4f r = rest.get(names[k]);
            Matrix4f a = animated.get(names[k]);
            check(r != null && a != null, "skin matrix missing for " + names[k]);
            Matrix4f skin = new Matrix4f(a).mul(new Matrix4f(r).invert());
            Vector3f p = skin.transformPosition(new Vector3f(v.x, v.y, v.z));
            out.x += p.x * w;
            out.y += p.y * w;
            out.z += p.z * w;
        }
        return out;
    }

    private static Map<String, Matrix4f> bentMatrices(ParsedModel model, String target, float angleRad) {
        Map<String, Matrix4f> out = new HashMap<>();
        for (ParsedBone root : model.roots) {
            buildBent(root, new Matrix4f(), out, target, angleRad);
        }
        return out;
    }

    private static void buildBent(ParsedBone bone, Matrix4f parent, Map<String, Matrix4f> out,
                                  String target, float angleRad) {
        Matrix4f local = new Matrix4f(parent)
                .translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        Matrix4f restRot = restRotation(bone);
        if (restRot != null) {
            local.mul(restRot);
        } else {
            local.rotateZYX(bone.rotZ, bone.rotY, bone.rotX);
        }
        if (bone.name.equals(target)) {
            local.rotateZ(angleRad);
        }
        local.scale(bone.scaleX, bone.scaleY, bone.scaleZ);
        out.put(bone.name, local);
        for (ParsedBone child : bone.children) {
            buildBent(child, local, out, target, angleRad);
        }
    }

    private static Matrix4f restRotation(ParsedBone bone) {
        if (bone.restMatrix == null || bone.restMatrix.length < 9) {
            return null;
        }
        float[] m = bone.restMatrix;
        return new Matrix4f().set3x3(new Matrix3f(
                m[0], m[3], m[6],
                m[1], m[4], m[7],
                m[2], m[5], m[8]));
    }

    private static void expectFailure(ParsedModel model, String what) {
        try {
            model.toJson();
            throw new AssertionError("expected failure: " + what);
        } catch (IllegalStateException expected) {
            // expected: saving invalid skin data must fail
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
    }
}
