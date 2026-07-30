package sh.haven.feature.connections

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.ssh.SshKeyResolver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SshKeyResolverModule {
    @Binds
    @Singleton
    abstract fun bindSshKeyResolver(impl: SshKeyResolverImpl): SshKeyResolver
}
