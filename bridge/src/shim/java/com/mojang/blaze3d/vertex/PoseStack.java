package com.mojang.blaze3d.vertex;

import com.mojang.math.Axis;
import com.mojang.math.Transformation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Server-side stand-in for the client's matrix stack, written for the bridge. Only the matrix
 * maths is here; nothing draws. See {@code io.github.joddabodscripts.j2b.bridge.render}.
 */
public class PoseStack {
	private final Deque<Pose> poses = new ArrayDeque<>();

	public PoseStack() {
		this.poses.push(new Pose());
	}

	public void translate(double x, double y, double z) {
		translate((float) x, (float) y, (float) z);
	}

	public void translate(float x, float y, float z) {
		last().translate(x, y, z);
	}

	public void translate(Vec3 offset) {
		translate(offset.x, offset.y, offset.z);
	}

	public void scale(float x, float y, float z) {
		last().scale(x, y, z);
	}

	public void rotate(Quaternionfc rotation) {
		last().rotate(rotation);
	}

	public void rotate(Axis axis, float radians) {
		last().rotate(axis, radians);
	}

	public void rotateDegrees(Axis axis, float degrees) {
		last().rotateDegrees(axis, degrees);
	}

	public void rotateAround(Quaternionfc rotation, float x, float y, float z) {
		last().rotateAround(rotation, x, y, z);
	}

	public void pushPose() {
		this.poses.push(last().copy());
	}

	public void popPose() {
		this.poses.pop();
	}

	public Pose last() {
		return this.poses.peek();
	}

	public boolean isEmpty() {
		return this.poses.size() == 1;
	}

	public void setIdentity() {
		last().setIdentity();
	}

	public void mulPose(Matrix4fc matrix) {
		last().mulPose(matrix);
	}

	public void mulPose(Transformation transformation) {
		last().mulPose(transformation);
	}

	public static final class Pose {
		private final Matrix4f pose = new Matrix4f();
		private final Matrix3f normal = new Matrix3f();

		public Pose() {
		}

		public void set(Pose other) {
			this.pose.set(other.pose);
			this.normal.set(other.normal);
		}

		public Matrix4f pose() {
			return this.pose;
		}

		public Matrix3f normal() {
			return this.normal;
		}

		public Vector3f transformNormal(Vector3fc normal, Vector3f out) {
			return this.normal.transform(normal, out);
		}

		public Vector3f transformNormal(float x, float y, float z, Vector3f out) {
			return this.normal.transform(x, y, z, out);
		}

		public Matrix4f translate(float x, float y, float z) {
			return this.pose.translate(x, y, z);
		}

		public void scale(float x, float y, float z) {
			this.pose.scale(x, y, z);
		}

		public void rotate(Quaternionfc rotation) {
			this.pose.rotate(rotation);
			this.normal.rotate(rotation);
		}

		public void rotate(Axis axis, float radians) {
			rotate(axis.rotation(radians));
		}

		public void rotateDegrees(Axis axis, float degrees) {
			rotate(axis.rotationDegrees(degrees));
		}

		public void rotateAround(Quaternionfc rotation, float x, float y, float z) {
			this.pose.rotateAround(rotation, x, y, z);
			this.normal.rotate(rotation);
		}

		public void setIdentity() {
			this.pose.identity();
			this.normal.identity();
		}

		public void mulPose(Matrix4fc matrix) {
			this.pose.mul(matrix);
		}

		public void mulPose(Transformation transformation) {
			mulPose(transformation.getMatrix());
		}

		public Pose copy() {
			Pose copy = new Pose();
			copy.set(this);
			return copy;
		}
	}
}
