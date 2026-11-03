// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
#pragma once
#include <string>
namespace mobile {
void configure_backend(const std::string &mode, const std::string &libraries,
                       const std::string &state_path);
void boot_begin();
void boot_ready(const std::string &capabilities);
void boot_failed(const std::string &error);
std::string boot_snapshot();
} // namespace mobile
