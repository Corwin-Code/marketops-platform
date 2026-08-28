# Where marketplace evidence lives, and what stops it from changing.
#
# The product's rule is that a success or a business-meaningful failure is kept
# byte-for-byte, and that a stored object's hash and length are what a later
# claim rests on. The application enforces that; this bucket enforces it
# independently, because a rule that only one layer holds is a rule one defect
# away from not holding at all.
#
# Object lock in compliance mode means nobody — not an operator, not the account
# owner, not a compromised workload identity — can shorten an object's retention
# or delete it before it expires. That is deliberately inconvenient. Evidence
# that can be deleted by whoever is embarrassed by it is not evidence.

terraform {
  required_version = ">= 1.14.9, < 2.0.0"

  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "= 0.220.0"
    }
  }
}

resource "yandex_storage_bucket" "evidence" {
  bucket        = var.bucket_name
  folder_id     = var.folder_id
  force_destroy = false

  # Versioning is a precondition for object lock, and it is also what makes an
  # accidental overwrite recoverable rather than final.
  versioning {
    enabled = true
  }

  object_lock_configuration {
    object_lock_enabled = "Enabled"

    rule {
      default_retention {
        mode = "COMPLIANCE"
        days = var.retention_days
      }
    }
  }

  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        kms_master_key_id = var.kms_key_id
        sse_algorithm     = "aws:kms"
      }
    }
  }

  # Objects become cheaper to keep as they age, and they are never deleted
  # before the lock expires. The transition is a cost decision; the absence of
  # an expiration rule shorter than the lock is a correctness one.
  lifecycle_rule {
    id      = "cool-after-ninety-days"
    enabled = true

    transition {
      days          = 90
      storage_class = "COLD"
    }
  }

  # An incomplete upload holds storage nobody can read. Cleaning them up is not
  # a retention decision, because there is no complete object to retain.
  lifecycle_rule {
    id      = "abandon-incomplete-uploads"
    enabled = true

    abort_incomplete_multipart_upload_days = 7
  }

  anonymous_access_flags {
    read        = false
    list        = false
    config_read = false
  }

  https {
    certificate_id = var.certificate_id
  }

  tags = var.labels
}

# Nothing may reach the bucket except the workload identity that writes evidence
# and the one that reads it back. The policy is written as a denial of everyone
# else rather than as a grant to those two, because a grant list omits whoever
# is added later and a denial does not.
resource "yandex_storage_bucket_policy" "evidence" {
  bucket = yandex_storage_bucket.evidence.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "WorkloadsCannotDeleteOrRelaxCustody"
        Effect    = "Deny"
        Principal = "*"
        Action    = ["s3:DeleteObject", "s3:DeleteObjectVersion", "s3:PutObjectRetention", "s3:PutBucketPolicy", "s3:DeleteBucketPolicy"]
        Resource  = ["arn:aws:s3:::${var.bucket_name}", "arn:aws:s3:::${var.bucket_name}/*"]
        Condition = { StringEquals = { "aws:userid" = var.permitted_service_account_ids } }
      },
      {
        Sid       = "RefuseUnencryptedTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          "arn:aws:s3:::${var.bucket_name}",
          "arn:aws:s3:::${var.bucket_name}/*",
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
      {
        Sid       = "RefuseEveryoneExceptTheEvidenceWorkloads"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          "arn:aws:s3:::${var.bucket_name}",
          "arn:aws:s3:::${var.bucket_name}/*",
        ]
        Condition = {
          StringNotEquals = {
            "aws:userid" = concat(var.permitted_service_account_ids, [var.infrastructure_service_account_id])
          }
        }
      },
    ]
  })
}

resource "yandex_storage_bucket_iam_binding" "readers" {
  bucket  = yandex_storage_bucket.evidence.bucket
  role    = "storage.viewer"
  members = [for id in var.permitted_service_account_ids : "serviceAccount:${id}"]
}
resource "yandex_storage_bucket_iam_binding" "writers" {
  bucket  = yandex_storage_bucket.evidence.bucket
  role    = "storage.uploader"
  members = [for id in var.permitted_service_account_ids : "serviceAccount:${id}"]
}
resource "yandex_kms_symmetric_key_iam_binding" "evidence" {
  symmetric_key_id = var.kms_key_id
  role             = "kms.keys.encrypterDecrypter"
  members          = [for id in var.permitted_service_account_ids : "serviceAccount:${id}"]
}
