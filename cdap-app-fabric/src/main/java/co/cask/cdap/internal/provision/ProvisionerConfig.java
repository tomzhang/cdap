/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.provision;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Contains the config for the provisioner
 */
public class ProvisionerConfig {
  @SerializedName("configuration-groups")
  private final List<Object> configurationGroups;
  @SerializedName("icon")
  private final Object icon;
  @SerializedName("beta")
  private final Boolean beta;

  public ProvisionerConfig(List<Object> configurationGroups, @Nullable Object icon, @Nullable Boolean beta) {
    this.configurationGroups = configurationGroups;
    this.icon = icon;
    this.beta = beta;
  }

  public List<Object> getConfigurationGroups() {
    return configurationGroups;
  }

  @Nullable
  public Object getIcon() {
    return icon;
  }

  @Nullable
  public Boolean isBeta() {
    return beta;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProvisionerConfig that = (ProvisionerConfig) o;

    return Objects.equals(configurationGroups, that.configurationGroups) &&
      Objects.equals(configurationGroups, that.configurationGroups) &&
      Objects.equals(beta, that.beta);
  }

  @Override
  public int hashCode() {
    return Objects.hash(configurationGroups, icon, beta);
  }
}
